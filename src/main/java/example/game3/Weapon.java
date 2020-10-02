package example.game3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class Weapon {
    public String getName() {
        return name;
    }

    public Integer getRarity() {
        return rarity;
    }

    public Integer getHitpoints() {
        return hitpoints;
    }

    private String name;
    private static Integer totalRarity = Integer.valueOf(0);
    private Integer rarity, hitpoints;
    private static ArrayList<Range> rarityRanges = new ArrayList<Range>();
    private final String type = "weapon";

    private static Map<String,Weapon> allWeapons = new HashMap<String,Weapon>();

    public static Integer getTotalRarity() {
        return totalRarity;
    }

    Weapon(String name, Integer rarity, Integer hitpoints) {
        this.name = name;
        this.rarity = rarity;
        this.hitpoints = hitpoints;

        int rareTop = totalRarity + rarity;
        rarityRanges.add(new Range(totalRarity, rareTop, this));

        totalRarity = totalRarity + rarity;

        allWeapons.put(this.name, this);
    }

    static Weapon randWeapon() {
        int i = ThreadLocalRandom.current().nextInt(totalRarity);
        for (Range r : rarityRanges) {
            if (r.covers(i))  {
                return r.getWeapon();
            }

        }
        return null;
    }

    public static Weapon getWeaponByName(String name) {
        Weapon found = allWeapons.get(name);
        if (found == null) {
            throw new RuntimeException("Requested a nonexistant weapon: " + name);
        }
        return found;
    }

    class Range {
        int low, high;
        Weapon weapon;
        Range (int low, int high, Weapon weapon) {
            this.low = low;
            this.high = high;
            this.weapon = weapon;
        }

        public boolean covers(int i) {
            if (i >= low && i<= high) {
                return true;
            }
            return false;
        }

        public Weapon getWeapon() {
            return this.weapon;
        }
    };

}
