package com.example.waystonesqmap.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collections;

public class FavoritesManager {
    private static final Gson GSON = new Gson();
    private static final String FILENAME = "waystones_sqmap_favorites.json";
    private static List<UUID> favorites = new ArrayList<>();
    private static boolean loaded = false;

    public static List<UUID> getFavorites() {
        if (!loaded) load();
        return favorites;
    }

    public static void add(UUID id) {
        if (!loaded) load();
        if (!favorites.contains(id)) {
            favorites.add(id);
            save();
        }
    }

    public static void remove(UUID id) {
        if (!loaded) load();
        favorites.remove(id);
        save();
    }
    
    public static void moveUp(UUID id) {
        if (!loaded) load();
        int index = favorites.indexOf(id);
        if (index > 0) {
            Collections.swap(favorites, index, index - 1);
            save();
        }
    }

    public static void moveDown(UUID id) {
        if (!loaded) load();
        int index = favorites.indexOf(id);
        if (index >= 0 && index < favorites.size() - 1) {
            Collections.swap(favorites, index, index + 1);
            save();
        }
    }

    public static boolean contains(UUID id) {
        if (!loaded) load();
        return favorites.contains(id);
    }

    private static void load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        File file = configDir.resolve(FILENAME).toFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Type type = new TypeToken<ArrayList<UUID>>(){}.getType();
                favorites = GSON.fromJson(reader, type);
                if (favorites == null) favorites = new ArrayList<>();
            } catch (Exception e) {
                e.printStackTrace();
                favorites = new ArrayList<>();
            }
        }
        loaded = true;
    }

    private static void save() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        File file = configDir.resolve(FILENAME).toFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(favorites, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
