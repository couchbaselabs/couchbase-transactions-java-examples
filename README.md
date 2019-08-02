# Couchbase Transactions example app
This example is based on the Game Simulation sample bucket provided with Couchbase.

The sample data simulates that of a simple Massively Multiplayer Online game, and includes documents representing:

* Players, with experience points and levels
* Monsters, with hitpoints and the number of experience points a player earns from their death

However, the Game Simulation sample bucket does not have to be installed.  This application will create all required
data in the specified bucket.

In this example, the player is dealing damage to the monster.  The player’s client has sent this instruction to a central
server, where we’re going to record that action.  We’re going to do this in a transaction, as we don’t want a situation
where the monster is killed, but we fail to update the player’s document with the earned experience.

The app takes command-line arguments that point to a particular Couchbase cluster and bucket.  The app will:

- Upsert a player document "player_jane" on this bucket.
- Upsert a monster document "a_grue" on this bucket.
- Run a single transaction where player_jane does a random amount of damage to a_grue.  This has a 50% chance of killing
  (removing) the monster, which will gain player_jane experience.

## Running
Run
```
./gradlew run --args="--cluster <CLUSTER_IP> --username <CLUSTER_USERNAME> --password <CLUSTER_PASSWORD> --bucket <BUCKET_NAME>"
```
Details of what's going on in the transaction will be logged to stdout.

You can run with the `--verbose` flag to also display full transactions trace to stdout, or the `--help` flag.

