package example.docker;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.transactions.TransactionDurabilityLevel;
import com.couchbase.transactions.Transactions;
import com.couchbase.transactions.config.TransactionConfigBuilder;
import com.couchbase.transactions.log.TransactionEvent;
import com.moandjiezana.toml.Toml;
import example.transfer.TransferExample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Starts single process, configures self from files mounted in to the container (username, cluster, password) or ENVVARS
 * Runs a small loop (probably need some tuneables from ^) that carries out a transaction
 * Runs a small web page that shows what it’s doing, maybe just number of txns done or some other counter.  no fancy formatting, that’s for Grafana
 * Exports stats in Prometheus format, like retries  (the format is very simple and well documented)
 * Can be configured with OTel
 *
 *
 * The way I hope to use this is have a cluster and the app both in K8S, and demonstrate scaling up the “service” that is doing transactions, seeing the increased throughput and possibly latency through the stats/OTel.
 */
@SpringBootApplication
public class Application {
	public static final AtomicInteger transactionCount = new AtomicInteger(0);
	private final Logger logger = LoggerFactory.getLogger(Application.class);

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
		return args -> {
			if (args.length != 1) {
				logger.error("Must provide location of configuration file");
				System.exit(-1);
			}

			File configFile = new File(args[0]);

			if (!configFile.exists()) {
				logger.error("Cannot find config file '" + args[0] + "'");
				System.exit(-1);
			}

			Toml toml = new Toml().read(configFile);

			try {
				String clusterName = toml.getString("cluster.host");
				String username = toml.getString("cluster.username");
				String password = toml.getString("cluster.password");
				String bucketName = toml.getString("cluster.bucket");
				boolean flushBucket = toml.getBoolean("cluster.flush_bucket");

				String durability = toml.getString("transactions.durability");
				long iterations = toml.getLong("transactions.iterations");
				boolean verbose = toml.getBoolean("transactions.verbose_logging");

				long amount = toml.getLong("transfer.amount");

				TransactionDurabilityLevel transactionDurabilityLevel = TransactionDurabilityLevel.MAJORITY;
				switch (durability.toLowerCase()) {
					case "none":
						transactionDurabilityLevel = TransactionDurabilityLevel.NONE;
						break;
					case "majority":
						transactionDurabilityLevel = TransactionDurabilityLevel.MAJORITY;
						break;
					case "persist_to_majority":
						transactionDurabilityLevel = TransactionDurabilityLevel.PERSIST_TO_MAJORITY;
						break;
					case "majority_and_persist":
						transactionDurabilityLevel = TransactionDurabilityLevel.MAJORITY_AND_PERSIST_TO_ACTIVE;
						break;
					default:
						System.out.println("Unknown durability setting " + durability);
						System.exit(-1);
				}


				logger.info("Connecting to cluster {} and opening bucket {}", clusterName, bucketName);

				// Initialize the Couchbase cluster
				Cluster cluster = Cluster.connect(clusterName, username, password);
				Bucket bucket = cluster.bucket(bucketName);
				Collection collection = bucket.defaultCollection();
				bucket.waitUntilReady(Duration.ofSeconds(30));

				if (flushBucket) {
					cluster.buckets().flushBucket(bucketName);
				}

				logger.info("Connected to cluster, starting transactions");

				// Create Transactions config
				TransactionConfigBuilder config = TransactionConfigBuilder.create()
						.durabilityLevel(transactionDurabilityLevel);

				// Initialize transactions.  Must only be one Transactions object per app as it creates background resources.
				Transactions transactions = Transactions.create(cluster, config);

				// Optional but recommended - subscribe for events
				cluster.environment().eventBus().subscribe(event -> {
					if (event instanceof TransactionEvent) {

						TransactionEvent te = (TransactionEvent) event;

						if (te.severity().ordinal() >= Event.Severity.WARN.ordinal()) {
							// handle important event
						}
					}
				});

				for (long i = 0; i < iterations; i ++) {
					// Setup test data
					JsonObject customer1 = JsonObject.create()
							.put("type", "Customer")
							.put("name", "Andy")
							.put("balance", 100);

					JsonObject customer2 = JsonObject.create()
							.put("type", "Customer")
							.put("name", "Beth")
							.put("balance", 100);

					String customer1Id = UUID.randomUUID().toString();
					String customer2Id = UUID.randomUUID().toString();

					collection.upsert(customer1Id, customer1);
					collection.upsert(customer2Id, customer2);

					TransferExample.transferMoney(transactions, collection, customer1Id, customer2Id, amount);

					transactionCount.incrementAndGet();
				}
			} catch (RuntimeException e) {
				System.err.println("Failed: " + e);
				System.exit(-1);
			}
		};
	}

}