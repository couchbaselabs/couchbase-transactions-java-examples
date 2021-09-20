# Couchbase Distributed Transactions Examples
This code-base includes two examples of using the Java implementation of Couchbase Distributed Transactions.

## Pre-requisites

- Couchbase Server 6.6
- A created bucket.  The examples will create documents in this bucket, and the Docker example can optionally flush it, so it's best to create a temporary bucket.
  If using a single-node cluster (for example during local development), make sure that the bucket has 0 replicas configured.
  The default is 1 replica, and this will cause any durable writes (which transactions use, by default), to fail.
- JDK 11+.

## Game Example
This example uses the Game Simulation sample bucket provided with Couchbase.

The sample data simulates that of a simple Massively Multiplayer Online game, and includes documents representing:

* Players, with experience points and levels
* Monsters, with hitpoints and the number of experience points a player earns from their death

However, the Game Simulation sample bucket does not have to be installed.  This application will create all required
data in the specified bucket.

In this example, the player is dealing damage to the monster.  The player’s client has sent this instruction to a central
server, where we’re going to record that action.  We’re going to do this in a transaction, as we don’t want a situation
where the monster is killed, but we fail to update the player’s document with the earned experience.

Note that it's a perfectly valid choice for an application to choose to not use a transaction here.  
There is some unavoidable performance impact to using transactions, in any database, and the application may value performance over atomicity in this case - especially as a failure will have limited impact, e.g. a player may not receive the experience they deserve, or a monster may not be felled when it should. 

### Running
The app takes command-line arguments that point to a particular Couchbase cluster and bucket.  The app will:

- Upsert a player document "player_jane" on this bucket.
- Upsert a monster document "a_grue" on this bucket.
- Run a single transaction where player_jane does a random amount of damage to a_grue.  This has a 50% chance of killing
  (removing) the monster, which will gain player_jane experience.

Run:
```
./gradlew game --args="--cluster <CLUSTER_IP> --username <CLUSTER_USERNAME> --password <CLUSTER_PASSWORD> --bucket <BUCKET_NAME>"
```
Details of what's going on in the transaction will be logged to stdout.

You can run with the `--verbose` flag to also display full transactions trace to stdout, or the `--help` flag.

## Transfer Example
This example simulates a bank transfering an amount between two customers, and creating a record of the event.

Note that this is an unrealistic example, as a real financial example would typically not directly debit a user's balance but instead record two separate events to the two accounts, for the credit and the debit.
But it suffices for a simplified example.

### Running
The app takes command-line arguments that point to a particular Couchbase cluster and bucket.  The app will:

- Upsert customer documents "andy" and "beth" on this bucket, with a balance of 100 apiece.
- Create a transaction that debits Andy's account by a specified amount and credits it to Beth.
- The transaction also inserts a new record with a random UUID that records the transfer. 

Run:
```
./gradlew transfer --args="--cluster <CLUSTER_IP> --username <CLUSTER_USERNAME> --password <CLUSTER_PASSWORD> --bucket <BUCKET_NAME> --amount 80"
```

Details of what's going on in the transaction will be logged to stdout.

You can run with the `--verbose` flag to also display full transactions trace to stdout, or the `--help` flag.

## Docker Example
This is WIP and experimental.

### Setup
Copy `config.example.toml` to `config.toml` and edit it for your Couchbase cluster configuration.

#### OpenTelemetry (Optional)
The application can optionally output OpenTelemetry data that can be used for performance analysis.

There are multiple tools for capturing and analysing this OpenTelemetry data, and several are supported.

##### OpenTelemetry Collector
This tool receives OpenTelemetry spans on port 4317 and then generally sends it on to another tool, such as Zipkin, Jaeger, Honeycomb, Lightstep or other.

It is increasingly recommended as a best practice when working with OpenTelemetry, as it provides an abstraction allowing the ultimate span destination to be changed at runtime without modifying or restarting the application.

The collector can be run with:

  ```docker run -v "${PWD}/opentelemetry-config.example.yaml:/etc/otel-local-config.yaml" -p 4317:4317 otel/opentelemetry-collector  --config /etc/otel-local-config.yaml```

Then uncomment the otlp_endpoint line in `config.toml` to enable sending to this.

The default `opentelemetry-config.example.yaml` just logs spans to console.
See the [OpenTelemetry documentation](https://opentelemetry.io/docs/collector/configuration/) for how to configure it further. 

##### Zipkin
Zipkin can be run with:

```docker run -d -p 9411:9411 openzipkin/zipkin```

Then uncomment the zipkin_endpoint line in `config.toml` to enable sending to this. 

The output can be viewed at `http://localhost:9411/zipkin/`.

##### Jaegar
Jaegar can be run with:

```
docker run -d --name jaeger \
  -e COLLECTOR_ZIPKIN_HOST_PORT=:9411 \
  -p 5775:5775/udp \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 14268:14268 \
  -p 14250:14250 \
  -p 9411:9411 \
  jaegertracing/all-in-one:1.26
```

Jaegar provides a Zipkin-compatible endpoint running on the same port 9411. 
So the instructions to use Jaegar are the same as for Zipkin: simply uncomment the zipkin_endpoint line in `config.toml`.

#### Prometheus (Optional)
Optionally, run Prometheus for metrics capture.
Create a Prometheus config:
```
scrape_configs:
  - job_name: 'prometheus'

    scrape_interval: 5s

    static_configs:
      - targets: [
        # The Docker app - change the IP to your Docker container's
        '172.17.0.2:9000',

        # Prometheus itself, purely for sanity checking
        'localhost:9090']
```

And then run Prometheus with that config:
```
docker run --rm -p 9090:9090 -v prometheus.yml:/etc/prometheus/prometheus.yml prom/prometheus
```
You can now visit `http://localhost:9090` to visit the Prometheus UI.
The metrics exposed by the application are:
- `transaction_count` showing a cumulative count of the number of transactions.
- `transaction_latency_bucket` showing a histogram of transaction latencies.

### Running
There are three ways of running the application from the command line:

Use:
```gradle bootRun -Pargs='config.toml'```

Or use Docker:
```docker build --tag couchbase-transactions-example:0.1 .```

```docker run --rm -t --publish 8080:8080 --name te couchbase-transactions-example:0.1```

Or use Kubernetes together with Docker - see the `k8s-run` folder for an example.

Once running, a very basic web-server is available on port 8080, showing the count of transactions.