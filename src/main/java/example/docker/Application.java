package example.docker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.transactions.GlobalTracerHack;
import com.couchbase.transactions.TransactionDurabilityLevel;
import com.couchbase.transactions.Transactions;
import com.couchbase.transactions.config.TransactionConfigBuilder;
import com.couchbase.transactions.log.TransactionEvent;
import com.moandjiezana.toml.Toml;
import example.transfer.TransferExample;
import io.opentelemetry.exporters.zipkin.ZipkinSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.Samplers;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.TracerSdkProvider;
import io.opentelemetry.sdk.trace.config.TraceConfig;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.Tracer;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

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
	public static final Counter transactionCount = Counter.build()
			.name("transactions_count").help("Number of transactions").register();
	static final Histogram requestLatency = Histogram.build()
			.name("transaction_latency").help("Transaction latency in milliseconds").register();
	private final Logger logger = LoggerFactory.getLogger(Application.class);

	@Autowired
	private Environment env;

	public static void main(String[] args) {
		DefaultExports.initialize();
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
				String clusterHost = toml.getString("cluster.host");
				String username = toml.getString("cluster.username");
				String password = toml.getString("cluster.password");
				String bucketName = toml.getString("cluster.bucket");
				boolean flushBucket = toml.getBoolean("cluster.flush_bucket");

				String durability = toml.getString("transactions.durability");
				long iterations = toml.getLong("transactions.iterations");
				boolean verbose = toml.getBoolean("transactions.verbose_logging");
				int prometheusPort = toml.getLong("prometheus.port").intValue();

				String zipkinEndpoint = toml.getString("open_telemetry.zipkin_endpoint");

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
						logger.info("No durability specified; using defaults.");
				}

				// Override with anything from the config map and load secrets
				try {
					username = Files.readString(Path.of("/deployments/txnexample/creds/username"), StandardCharsets.UTF_8);
					password = Files.readString(Path.of("/deployments/txnexample/creds/password"), StandardCharsets.UTF_8);
					logger.info("Loaded secrets from K8S environment…");
					clusterHost = Files.readString(Path.of("/deployments/txnexample/config/clusterHost"), StandardCharsets.UTF_8);
					bucketName = Files.readString(Path.of("/deployments/txnexample/config/bucketName"), StandardCharsets.UTF_8);
					verbose = Boolean.parseBoolean(Files.readString(Path.of("/deployments/txnexample/config/verbose"), StandardCharsets.UTF_8));
					logger.info("Loaded config from K8S environment…");
				} catch (IOException e) {
					logger.info("Failed to retrieve secrets or config map at startup.");
				}


				logger.info("Connecting to cluster {} and opening bucket {} as user {}", clusterHost, bucketName, username);

				// Initialize the Couchbase cluster
				Cluster cluster = Cluster.connect(clusterHost, username, password);
				Bucket bucket = cluster.bucket(bucketName);
				Collection collection = bucket.defaultCollection();
				bucket.waitUntilReady(Duration.ofSeconds(30));

				if (flushBucket) {
					cluster.buckets().flushBucket(bucketName);
				}

				Tracer tracer = configureOpenTelemetry(zipkinEndpoint);

				// Prometheus server
				HTTPServer server = new HTTPServer(prometheusPort);

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

					Histogram.Timer requestTimer = requestLatency.startTimer();

					TransferExample.transferMoney(transactions, collection, customer1Id, customer2Id, amount);

					requestTimer.observeDuration();
					transactionCount.inc();
				}
			} catch (RuntimeException e) {
				logger.error("Failed: {}, pausing 60s before exit", e.toString());
				System.err.println("Failed: " + e);
				Thread.sleep(60000);
				logger.error("Exiting.");
				System.exit(-1);
			}
		};
	}

	private static Tracer configureOpenTelemetry(String zipkinEndpoint) {
		TracerSdkProvider tracer = OpenTelemetrySdk.getTracerProvider();

		ZipkinSpanExporter exporter =
				ZipkinSpanExporter.newBuilder()
						.setEndpoint(zipkinEndpoint)
						.setServiceName("transactions-example")
						.build();

		SpanProcessor processor = BatchSpanProcessor.newBuilder(exporter).build();
		tracer.addSpanProcessor(processor);

		TraceConfig alwaysOn = TraceConfig.getDefault().toBuilder().setSampler(
				Samplers.alwaysOn()
		).build();
		tracer.updateActiveTraceConfig(alwaysOn);

		GlobalTracerHack.globalTracer = tracer.get("transactions-example");

		return GlobalTracerHack.globalTracer;
	}

}