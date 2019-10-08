package example.game;

import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.transactions.TransactionGetResult;
import com.couchbase.transactions.Transactions;
import com.couchbase.transactions.error.TransactionFailed;
import com.couchbase.transactions.log.LogDefer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameServer {
    private final Transactions transactions;
    private final Collection gameSim;
    private final Logger logger = LoggerFactory.getLogger(GameServer.class);

    public GameServer(Transactions transactions, Collection gameSim) {
        this.transactions = transactions;
        this.gameSim = gameSim;
    }

    public void playerHitsMonster(String actionUuid, int damage, String playerId, String monsterId) {
        try {
            transactions.run((ctx) -> {
                logger.info("Starting transaction, player {} is hitting monster {} for {} points of damage",
                        playerId, monsterId, damage);

                TransactionGetResult monster = ctx.get(gameSim, monsterId);
                TransactionGetResult player = ctx.get(gameSim, playerId);

                JsonObject monsterContent = monster.contentAsObject();
                JsonObject playerContent = player.contentAsObject();

                int monsterHitpoints = monsterContent.getInt("hitpoints");
                int monsterNewHitpoints = monsterHitpoints - damage;

                logger.info("Monster {} had {} hitpoints, took {} damage, now has {} hitpoints",
                        monsterId, monsterHitpoints, damage, monsterNewHitpoints);

                if (monsterNewHitpoints <= 0) {
                    // Monster is killed.  The remove is just for demoing, and a more realistic example would set a
                    // "dead" flag or similar.
                    ctx.remove(monster);

                    // The player earns experience for killing the monster
                    int experienceForKillingMonster = monster.contentAs(JsonObject.class).getInt("experienceWhenKilled");
                    int playerExperience = player.contentAs(JsonObject.class).getInt("experience");
                    int playerNewExperience = playerExperience + experienceForKillingMonster;
                    int playerNewLevel = calculateLevelForExperience(playerNewExperience);

                    logger.info("Monster {} was killed.  Player {} gains {} experience, now has level {}",
                            monsterId, playerId, experienceForKillingMonster, playerNewLevel);

                    playerContent.put("experience", playerNewExperience);
                    playerContent.put("level", playerNewLevel);

                    ctx.replace(player, playerContent);
                }
                else {
                    logger.info("Monster {} is damaged but alive", monsterId);

                    // Monster is damaged but still alive
                    monsterContent.put("hitpoints", monsterNewHitpoints);

                    ctx.replace(monster, monsterContent);
                }

                logger.info("About to commit transaction");
            });
        } catch (TransactionFailed e) {
            // The operation timed out (the default timeout is 15 seconds) despite multiple attempts to commit the
            // transaction logic.   Both the monster and the player will be untouched.

            // This situation should be very rare.  It may be reasonable in this situation to ignore this particular
            // failure, as the downside is limited to the player experiencing a temporary glitch in a fast-moving MMO.

            // So, we will just log the error
            logger.warn("Failed to complete action " + actionUuid);
            for (LogDefer log: e.result().log().logs()) {
                logger.warn(log.toString());
            }
        }

        logger.info("Transaction is complete");
    }

    private int calculateLevelForExperience(int exp) {
        return exp / 100;
    }
}
