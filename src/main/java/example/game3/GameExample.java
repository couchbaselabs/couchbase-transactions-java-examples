package example.game3;

import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.core.error.DocumentExistsException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.MutationResult;
import com.couchbase.client.java.kv.ReplaceOptions;
import com.couchbase.client.java.kv.UpsertOptions;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.transactions.TransactionDurabilityLevel;
import com.couchbase.transactions.TransactionGetResult;
import com.couchbase.transactions.Transactions;
import com.couchbase.transactions.config.TransactionConfigBuilder;
import com.couchbase.transactions.error.TransactionCommitAmbiguous;
import com.couchbase.transactions.error.TransactionFailed;
import com.couchbase.transactions.log.TransactionEvent;
import com.devskiller.jfairy.Fairy;
import com.devskiller.jfairy.producer.person.Person;
import example.docker.Application;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An example of Couchbase Distributed Transactions: please see the README for details.
 */

/*
 the game story

v1
(simple)
entities: player, monster (definition), item(definition of properies)

rules:
player fights a single monster, wins or loses.  if win: get random item.  if
lose: lose experience level.

PROBLEM: no collaborative play against a monster

v2
(simple)
entites: players, monster (entity), item (definition of properties)

rules:
players fight monster together, if win: get random item, if lose: lose
experience level.

BUG: two players can deliver the death blow, both win.  operator not
happy, excessive items.

v2.1
(shows CAS)
entities: player, monster (entity), item (definition of properties)

v3
entites: player, monster, item (definition of properties)
feature: intro'd trading to increase social interaction

rules:
players fight monster together, if win: get random item, if lose: lose
experience level.
players can trade with each other in the bazzar


BUG: players can exploit the system to duplicate items

v3.11 Gaming for Workgroups
entities:
(shows atomicity, retains CAS)
entites: player, monster, item

SUCCESS: items are traded atomically

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
                config.durabilityLevel(TransactionDurabilityLevel.MAJORITY_AND_PERSIST_TO_ACTIVE);
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
        bucket.waitUntilReady(Duration.ofSeconds(30));

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
        cluster.disconnect();
    }

    private static ArrayList<String> players = new ArrayList<String>();

    // See http://en.wikipedia.org/wiki/Category:Celtic_legendary_creatures
    private static final String[] monsters = {"Bauchan", "Fachen", "Fuath", "Joint-eater", "Kelpie",
            "Knocker", "Merrow", "Morgen", "Pictish-beast", "Wild-man"};

    public static String randPlayer() {
        return players.get(ThreadLocalRandom.current().nextInt(players.size()));
    }

    public static String randMonster() {
        return monsters[ThreadLocalRandom.current().nextInt(monsters.length)];
    }

    public static void battle(Transactions transactions, Collection collection, String player, String monster) {
        logger.debug("{} battles {}", player, monster);

        GetResult battlingPlayer = collection.get(player);
        GetResult battlingMonster = collection.get("m:" + monster);

        if (ThreadLocalRandom.current().nextBoolean()) {
            // player wins!
            int newHitpoints = battlingMonster.contentAsObject().getInt("hitpoints") - 1;
            if (newHitpoints == 0) {
                // player gets something
                // reset monster hitpoints
            }
            ReplaceOptions opts = ReplaceOptions.replaceOptions().cas(battlingMonster.cas());
            collection.replace("m:" + monster, battlingMonster.contentAsObject().put("hitpoints", newHitpoints), opts);

        } else {
            // monster wins!
        }

    }


    public static void setupData(Cluster cluster, Collection collection) throws InterruptedException {

        boolean dataLoader = true;
        JsonObject playerOne = JsonObject.create()
                .put("name", "Matt Ingenthron")
                .put("hitpoints", 1000)
                .put("experience", 100)
                .put("uuid", UUID.randomUUID().toString())
                .put("type", "player");
        String keyOne = "u:ingenthr";  // your truly!
        try {
            MutationResult result = collection.insert(keyOne, playerOne);
        } catch (DocumentExistsException e) {
            logger.info("Data already added, exiting setup");
            dataLoader = false;
        }
        players.add(keyOne);

        if (!dataLoader) {
            QueryResult queryResult = cluster.query("SELECT `" + Application.getBucketName() + "`.meta.id() WHERE type = \"player\"");
//            players.addAll(queryResult.rowsAs(class java.lang.String));
            throw new UnsupportedOperationException("skipping dataload not done yet");
            return;
        }

        Fairy fairy = Fairy.create();

        for (int i=0; i<5000; i++) {
            Person aPerson = fairy.person();
            JsonObject player = JsonObject.create()
                    .put("name", aPerson.getFullName())
                    .put("hitpoints", 100)
                    .put("experience", 0)
                    .put("uuid", UUID.randomUUID().toString())
                    .put("coins", ThreadLocalRandom.current().nextInt(999))
                    .put("type", "player");
            String key = "u:" + aPerson.getUsername();
            collection.upsert(key, player); // there may be dupes
            players.add(key);
        }

        for (String monster : monsters) {
            JsonObject monsterObject = JsonObject.create()
                    .put("name", monster)
                    .put("hitpoints", 10) /* TODO: random */
                    .put("type", "monster");
            collection.insert("m:" + monster, monsterObject);
        }

    }

    public static void trade(Transactions transactions, Collection collection, String randPlayer1, String randPlayer2) {

        // here beith a transaction!

        try {

            // Supply transactional logic inside a lambda - any required retries are handled for you
            transactions.run(ctx -> {

                // getOrError means "fail the transaction if that key does not exist"
                TransactionGetResult player1 = ctx.get(collection, randPlayer1);
                TransactionGetResult player2 = ctx.get(collection, randPlayer2);
                // Optional<TransactionJsonDocument> customer2Opt = ctx.get(collection, customer2Id);

                JsonObject player1Content = player1.contentAsObject();
                JsonObject player2Content = player2.contentAsObject();

                logger.info("In transaction - got player 1's details: " + player1Content);
                logger.info("In transaction - got player 2's details: " + player2Content);

//                int customer1Balance = player1Content.getInt("coins");
//                int customer2Balance = player2Content.getInt("coins");
//
//                if (customer1Balance >= amount) {
//                    logger.info("In transaction - customer 1 has sufficient balance, transferring " + amount);
//
//                    player1Content.put("balance", customer1Balance - amount);
//                    player2Content.put("balance", customer2Balance + amount);
//
//                    logger.info("In transaction - changing customer 1's balance to: " + player1Content.getInt("balance"));
//                    logger.info("In transaction - changing customer 2's balance to: " + player2Content.getInt("balance"));
//
//                    ctx.replace(player1, player1Content);
//                    ctx.replace(player2, player2Content);
//                }
//                else {
//                    logger.info("In transaction - customer 1 has insufficient balance to transfer " + amount);
//
//                    // Rollback is automatic on a thrown exception.  This will also cause the transaction to fail
//                    // with a TransactionFailed containing this InsufficientFunds as the getCause() - see below.
//                    throw new InsufficientFunds();
//                }

                // If we reach here, commit is automatic.
                logger.info("In transaction - about to commit");
                // ctx.commit(); // can also, and optionally, explicitly commit
            });
        } catch (TransactionCommitAmbiguous err) {
            System.err.println("Transaction " + err.result().transactionId() + " possibly committed:");
            err.result().log().logs().forEach(System.err::println);
        } catch (TransactionFailed err) {

            // ctx.getOrError can raise a DocumentNotFoundException
            if (err.getCause() instanceof DocumentNotFoundException) {
                throw new RuntimeException("Could not find player.");
            }
            else {
                // Unexpected error - log for human review
                // This per-txn log allows the app to only log failures
                System.err.println("Transaction " + err.result().transactionId() + " did not reach commit:");

                err.result().log().logs().forEach(System.err::println);
            }
        }


        // Post-transaction, see the results:
//        JsonObject customer1 = collection.get(customer1Id).contentAsObject();
//        JsonObject customer2 = collection.get(customer2Id).contentAsObject();
//
//        logger.info("After transaction - got customer 1's details: " + customer1);
//        logger.info("After transaction - got customer 2's details: " + customer2);
//
//        if (transferId.get() != null) {
//            JsonObject transferRecord = collection.get(transferId.get()).contentAsObject();
//
//            logger.info("After transaction - transfer record: " + transferRecord);
//        }

    }
}
