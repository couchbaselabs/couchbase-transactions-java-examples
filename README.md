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

Copy `config.example.toml` to `config.toml` and edit it for your Couchbase cluster configuration.

Optionally, run Zipkin or Jaegar server for OpenTelemetry capture:
```docker run -d -p 9411:9411 openzipkin/zipkin```
The default `config.toml` configuration (see `zipkin_endpoint`) will send OpenTelemetry span data to port 9411 on the Docker host.
Can view the output at `http://localhost:9411/zipkin/`
Alternative you can run Jaegar instead and it will also work.

Two ways of running the application:

Either use:
```gradle bootRun -Pargs='config.toml'```

Or use Docker:
```docker build --tag couchbase-transactions-example:0.1 .```

```docker run --rm -t --publish 8080:8080 --name te couchbase-transactions-example:0.1```

A very basic web-server is available on port 8080, showing the count of transactions.