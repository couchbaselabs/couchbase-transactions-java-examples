package example.docker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.transactions.GlobalTracerHack;
import com.couchbase.transactions.TransactionDurabilityLevel;
import com.couchbase.transactions.Transactions;
import com.couchbase.transactions.config.TransactionConfigBuilder;
import com.couchbase.transactions.log.TransactionEvent;
import com.moandjiezana.toml.Toml;
import example.game3.GameExample;
import io.opentelemetry.exporters.zipkin.ZipkinSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.Samplers;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.TracerSdkProvider;
import io.opentelemetry.sdk.trace.config.TraceConfig;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
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

@SpringBootApplication
public class Application {
	public static final Counter transactionCount = Counter.build()
			.name("transactions_count").help("Number of transactions").register();
	static final Histogram requestLatency = Histogram.build()
			.buckets(0.0250, 0.0375, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 40, 100)
			.name("transaction_latency").help("Transaction latency in milliseconds").register();
	private final Logger logger = LoggerFactory.getLogger(Application.class);

	private static String bucketName;

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
				bucketName = toml.getString("cluster.bucket");
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
					Thread.sleep(2000);
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

				GameExample.setupData(cluster, collection);

				for (long i = 0; i < iterations; i ++) {

					Histogram.Timer requestTimer = requestLatency.startTimer();

					// battle 6 days, go to the store on the 7th
					for (int j = 0; j < 6; j++) {
						GameExample.battle(transactions, collection, GameExample.randPlayer(), GameExample.randMonster());
					}

					GameExample.trade(transactions, collection, GameExample.randPlayer(), GameExample.randPlayer());

					requestTimer.observeDuration();
					transactionCount.inc();
				}
				logger.info("Completed {} transactions.", transactionCount.get());
			} catch (RuntimeException e) {
				logger.error("Failed: {}, pausing 60s before exit", e.toString());
				System.err.println("Failed: " + e.getCause());
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

	public static String getBucketName() {
		return bucketName;
	}

}