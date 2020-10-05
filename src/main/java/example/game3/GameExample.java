package example.game3;

import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.core.error.CasMismatchException;
import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.DocumentExistsException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.*;
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
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.couchbase.client.java.kv.MutateInSpec.decrement;

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

        // Shutdown resources cleanly
        transactions.close();
        cluster.disconnect();
    }

    private static ArrayList<String> players = new ArrayList<String>();

    // See http://en.wikipedia.org/wiki/Category:Celtic_legendary_creatures
    private static final String[] monsters = {"Bauchan", "Fachen", "Fuath", "Joint-eater", "Kelpie",
            "Knocker", "Merrow", "Morgen", "Pictish-beast", "Wild-man"};

    // See http://www.ancientmilitary.com/celtic-warriors.htm
    private static Weapon[] weapons;

    public static String randPlayer() {
        return players.get(ThreadLocalRandom.current().nextInt(players.size()));
    }

    public static String randMonster() {
        return monsters[ThreadLocalRandom.current().nextInt(monsters.length)];
    }

    public static void battle(Transactions transactions, Collection collection, String player, String monster) {
        logger.debug("{} battles {}", player, monster);

        GetResult battlingPlayer = collection.get(player);
        JsonObject playerJson = battlingPlayer.contentAsObject();
        GetResult battlingMonster = collection.get("m:" + monster);
        JsonObject monsterJson = battlingMonster.contentAsObject();

        if (ThreadLocalRandom.current().nextBoolean()) {
            // player wins!
            int newHitpoints = monsterJson.getInt("hitpoints") - 1;
            assert newHitpoints >= 0;

            // if the player beats the monster, they win something
            if (newHitpoints == 0) {
                // player gets something
                Weapon wonItem = Weapon.randWeapon();
                JsonObject bPlayerItems = playerJson.getObject("items");
                if (bPlayerItems != null) {
                    Integer invOfWeapon = bPlayerItems.getInt(wonItem.getName());
                    if (invOfWeapon != null) {
                        bPlayerItems.put(wonItem.getName(), invOfWeapon + 1);
                    } else {
                        bPlayerItems.put(wonItem.getName(), 1);
                    }
                } else /* player had no items */ {
                    JsonObject newPlayerContent = playerJson.put("items", JsonObject.create().put(wonItem.getName(), 1));
                }

                // reset monster hitpoints
                int newMonsterHitpoints = ThreadLocalRandom.current().nextInt(10, 100);
                // arguably should be a CAS, but it doesn't impact game logic if two race to replace.
                collection.replace("m:" + monster, monsterJson.put("hitpoints", newMonsterHitpoints));
            }

            // increase player experience
            long newExperience = playerJson.getLong("experience").longValue();
            newExperience++;
            playerJson.put("experience", newExperience);

            // update the player
            try {
                collection.replace(player, playerJson, ReplaceOptions.replaceOptions().cas(battlingPlayer.cas()));
            } catch (CasMismatchException e) {
                // don't care; could happen since we're not really simulating sessions
                logger.warn("update of player {} failed due to cas write conflict", player);
            }

            // reduce the monster hitpoints using Sub-Document API
            try {
                collection.mutateIn("m:" + monster, Collections.singletonList(decrement("hitpoints", 1)));
            } catch (CouchbaseException e) {
                logger.error("Failed to Sub-Document decrement monster {}", "m:" + monster);
                throw new RuntimeException("Sub-Document decrement of monster failed.", e);
            }

        } else {
            // monster wins!

            // player loses experience
            long newExperience = playerJson.getLong("experience").longValue();
            newExperience--;
            if (newExperience < 0) {
                playerJson.put("experience", 0);
            } else {
                playerJson.put("experience", newExperience);
            }

            // replace the player
            try {
                collection.replace(player, playerJson, ReplaceOptions.replaceOptions().cas(battlingPlayer.cas()));
            } catch (CasMismatchException e) {
                // don't care; could happen since we're not really simulating sessions
                logger.warn("update of player {} failed due to cas write conflict", player);
            }

        }

    }

    public static void setupData(Cluster cluster, Collection collection) throws InterruptedException {

        boolean dataLoader = true;

        // Weapons, in DB, mostly static so just instantiating here
         weapons = new Weapon[] {
                 new Weapon("Javelin", 80, 1),
                 new Weapon("Harpoon", 40, 2),
                 new Weapon("Bow", 25, 5),
                 new Weapon("Sling", 100, 1),
                 new Weapon("Light Crossbow", 8, 8),
                 new Weapon("Spear", 30, 10),
                 new Weapon("Two-hand Hammer", 20, 6),
                 new Weapon("Sword", 10, 12),
                 new Weapon("Sword (two-sided)", 8, 15),
                 new Weapon("Long Sword", 6, 16),
                 new Weapon("Axe", 5, 22),
                 new Weapon("Claymore", 1, 25),
                 // javelins, harpoons, bows and slings
                 // pila or harpoon-type javelins were carried by Celtic champions
                 // light crossbows
                 // close-range weapons, spears, two-hand hammers, axes and swords would be used. The swords were initially short swords, but they later became long swords.
                 // The Celtic, Celtiberian (that’s a mixed Celtic and Iberian tribe) and Iberian tribes of Hibernia (modern Spain) fashioned a short double-sided sword that was ideal for stabbing. This weapon became the model for the gladius used by the Roman legions. The Celtic spear possessed relatively broad points and were a grand example of this weapon type. Axes, two-hand hammers and two-hand swords (Claymore) were also used, but they were rather rarer weapons.
         };

        JsonObject playerOne = JsonObject.create()
                .put("name", "Matt Ingenthron")
                .put("hitpoints", 1000)
                .put("experience", 100)
                .put("uuid", UUID.randomUUID().toString())
                .put("coins", 1000000)
                .put("type", "player");
        String keyOne = "u:ingenthr";  // your truly! and a loading sentinel value
        try {
            MutationResult result = collection.insert(keyOne, playerOne);
        } catch (DocumentExistsException e) {
            logger.info("Data already added, exiting setup");
            dataLoader = false;
        }
        players.add(keyOne);

        if (!dataLoader) {
            QueryResult queryResult = cluster.query("SELECT RAW meta().id FROM " + Application.getBucketName() + " WHERE type = \"player\"");
            for (String id : queryResult.rowsAs(String.class)) {
                players.add(id);
            }
            return;
        }

        Fairy fairy = Fairy.create();

        int i = 0;
        do {
            try {
                Person aPerson = fairy.person();
                JsonObject player = JsonObject.create()
                        .put("name", aPerson.getFullName())
                        .put("hitpoints", 100)
                        .put("experience", ThreadLocalRandom.current().nextInt(50))
                        .put("uuid", UUID.randomUUID().toString())
                        .put("coins", ThreadLocalRandom.current().nextInt(999))
                        .put("type", "player");
                String key = "u:" + aPerson.getUsername();
                collection.insert(key, player);
                players.add(key);
                i++;
            } catch (DocumentExistsException existsEx) {
                // don't care
            }
        } while ( i< 4999 /* 5000 with the sentinel */ );


        for (String monster : monsters) {
            JsonObject monsterObject = JsonObject.create()
                    .put("name", monster)
                    .put("hitpoints", ThreadLocalRandom.current().nextInt(999))
                    .put("type", "monster");
            collection.insert("m:" + monster, monsterObject);
        }

        for (Weapon w : weapons) {
            collection.insert("w:" + w.getName(), w);
        }

    }

    /**
     * Trade an item for coins between two specified random players.
     *
     * In game play simulation, this method demonstrates a transaction between two players. While this doesn't
     * show this, it is possible for a transaction to span collections or even buckets.
     *
     * @param transactions transactions instance to be used
     * @param collection collection where the users exist
     * @param randPlayer1 first player
     * @param randPlayer2 second player
     */
    public static void trade(Transactions transactions, Collection collection, String randPlayer1, String randPlayer2) {

        try {

            // Supply transactional logic inside a lambda - any required retries are handled for you
            transactions.run(ctx -> {

                // getOrError means "fail the transaction if that key does not exist"
                TransactionGetResult player1 = ctx.get(collection, randPlayer1);
                TransactionGetResult player2 = ctx.get(collection, randPlayer2);

                JsonObject player1Content = player1.contentAsObject();
                JsonObject player2Content = player2.contentAsObject();

                logger.debug("In transaction - got player 1's details: " + player1Content);
                logger.debug("In transaction - got player 2's details: " + player2Content);

                if (player1Content.getString("uuid").contentEquals(player2Content.getString("uuid"))) {
                    logger.debug("Cannot trade with self. The user {} with UUID {} was supplied for both sides of the trade",
                            randPlayer1, player1Content.getString("uuid"));
                    return;
                }

                // check to be sure there is something to trade
                JsonObject p2Items = player2Content.getObject("items");
                if (p2Items == null || p2Items.isEmpty()) {
                    logger.debug("No trade today, player2 has nothing to trade.");
                    return;
                }

                // randomly pick something to trade
                Set<String> itemsToTrade = p2Items.getNames();
                String itemToTrade = itemsToTrade
                        .toArray()[ThreadLocalRandom.current().nextInt(0, itemsToTrade.size())]
                        .toString();

                // make sure the trader has it in stock
                if (p2Items.getInt(itemToTrade) <1) {
                    logger.debug("No trade today, player 2's item is out of stock");
                    return;
                }

                // player 1 offers an appropriate number of coins for something from player 2
                Integer p1CurrentCoins = player1Content.getInt("coins");
                Integer tradeAmount = p1CurrentCoins / Weapon.getWeaponByName(itemToTrade).getRarity();

                if (tradeAmount < 1) {
                    logger.debug("No trade today, player1 has no coins.");
                    return;
                }

                logger.info("Trading {} coins for a {}", tradeAmount, itemToTrade);

                player1Content.put("coins", p1CurrentCoins - tradeAmount);
                player1Content.put("items", addToItems(player1Content.getObject("items"), itemToTrade));

                player2Content.put("coins", player2Content.getInt("coins") + tradeAmount);
                player2Content.put("items", removeFromItems(player2Content.getObject("items"), itemToTrade));

                ctx.replace(player1, player1Content);
                ctx.replace(player2, player2Content);

                // If we reach here, commit is automatic.
                logger.debug("In transaction - about to commit");
                ctx.commit(); // can also, and optionally, explicitly commit
            });
        } catch (TransactionCommitAmbiguous err) {
            // This could happen in certain system failure cases, e.g. network failure after sending the commit
            System.err.println("Transaction " + err.result().transactionId() + " possibly committed:");
            err.result().log().logs().forEach(System.err::println);
        } catch (TransactionFailed err) {
            // ctx.getOrError can raise a DocumentNotFoundException if not initialized completely
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
    }

    private static JsonObject addToItems(JsonObject currItems, String itemName) {
        if (currItems == null) /* player had no items */ {
            currItems = JsonObject.create().put(itemName, 1);
        } else {
            Integer invOfWeapon = currItems.getInt(itemName);
            if (invOfWeapon != null) {
                currItems.put(itemName, invOfWeapon + 1);
            } else {
                currItems.put(itemName, 1);
            }
        }
        return currItems;
    }

    private static JsonObject removeFromItems(JsonObject currItems, String itemToDecrement) {
        int newItemCount = currItems.getInt(itemToDecrement) - 1;
        if (newItemCount > 0) {
            currItems.put(itemToDecrement, currItems.getInt(itemToDecrement) - 1);
        } else {
            currItems.removeKey(itemToDecrement);
        }
        return currItems;
    }
}
