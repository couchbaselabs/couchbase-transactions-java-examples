package example.game;

import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.transactions.TransactionDurabilityLevel;
import com.couchbase.transactions.Transactions;
import com.couchbase.transactions.config.TransactionConfigBuilder;
import com.couchbase.transactions.log.TransactionEvent;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * An example of Couchbase Distributed Transactions: please see the README for details.
 */
public class GameExample {
    private static final Logger logger = LoggerFactory.getLogger(GameExample.class);

    public static void main(String[] args) {
        // Parse command line arguments
        ArgumentParser parser = ArgumentParsers.newFor("Couchbase Distributed Transactions Game Example").build()
                .defaultHelp(true)
                .description("An example demonstrating the Couchbase Distributed Transactions, Java implementation.");
        parser.addArgument("-c", "--cluster")
                .required(true)
                .help("Specify Couchbase cluster address");
        parser.addArgument("-u", "--username")
                .required(true)
                .help("Specify username of Couchbase user");
        parser.addArgument("-p", "--password")
                .required(true)
                .help("Specify password of Couchbase user");
        parser.addArgument("-b", "--bucket")
                .required(true)
                .help("Specify name of Couchbase bucket");
        parser.addArgument("-d", "--durability")
                .setDefault("majority")
                .type(Integer.class)
                .help("Durability setting to use: majority,none,persist_to_majority,majority_and_persist (default:majority)");
        parser.addArgument("-v", "--verbose")
                .setDefault(false)
                .action(Arguments.storeConst()).setConst(true)
                .type(Boolean.class)
                .help("Logs all transaction trace to stdout (very heavy)");
        try {
            Namespace ns = parser.parseArgs(args);
            run(ns);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
        }
    }

    private static void run(Namespace ns) {
        String clusterName = ns.getString("cluster");
        String username = ns.getString("username");
        String password = ns.getString("password");
        String bucketName = ns.getString("bucket");
        String durability = ns.getString("durability");

        TransactionConfigBuilder config = TransactionConfigBuilder.create();
        switch (durability.toLowerCase()) {
            case "none":
                config.durabilityLevel(TransactionDurabilityLevel.NONE);
                break;
            case "majority":
                config.durabilityLevel(TransactionDurabilityLevel.MAJORITY);
                break;
            case "persist_to_majority":
                config.durabilityLevel(TransactionDurabilityLevel.PERSIST_TO_MAJORITY);
                break;
            case "majority_and_persist":
                config.durabilityLevel(TransactionDurabilityLevel.MAJORITY_AND_PERSIST_ON_MASTER);
                break;
            default:
                System.out.println("Unknown durability setting " + durability);
                System.exit(-1);
        }
        if (ns.getBoolean("verbose")) {
            config.logDirectly(Event.Severity.VERBOSE);
        }

        // Initialize the Couchbase cluster
        Cluster cluster = Cluster.connect(clusterName, username, password);
        Bucket bucket = cluster.bucket(bucketName);
        Collection collection = bucket.defaultCollection();

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



        // The example.GameServer object emulates the central server for this game
        GameServer gameServer = new GameServer(transactions, collection);



        // Initialise some sample data - a player and a monster.  This is based on the Game Simulation sample bucket
        // provided with Couchbase, though that does not have to be installed.
        String playerId = "player_jane";
        JsonObject player = JsonObject.create()
                        .put("experience", 14248)
                        .put("hitpoints", 23832)
                        .put("jsonType", "player")
                        .put("level", 141)
                        .put("loggedIn", true)
                        .put("name", "Jane")
                        .put("uuid", UUID.randomUUID().toString());

        String monsterId = "a_grue";
        JsonObject monster = JsonObject.create()
                        .put("experienceWhenKilled", 91)
                        .put("hitpoints", 4000)
                        .put("itemProbability", 0.19239324085462631)
                        .put("jsonType", "monster")
                        .put("name", "Grue")
                        .put("uuid", UUID.randomUUID().toString());

        collection.upsert(playerId, player);

        logger.info("Upserted sample player document " + playerId);

        collection.upsert(monsterId, monster);

        logger.info("Upserted sample monster document " + monsterId);


        // Now perform the transaction
        // The player is hitting the monster for a certain amount of damage
        gameServer.playerHitsMonster(
                // This UUID identifies this action from the player's client
                UUID.randomUUID().toString(),

                // This has a 50% chance of killing the monster, which has 4000 hitpoints
                ThreadLocalRandom.current().nextInt(8000),

                playerId,
                monsterId);


        // Shutdown resources cleanly
        transactions.close();
        cluster.shutdown();
    }


}
