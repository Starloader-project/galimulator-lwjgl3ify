package de.geolykt.starloader.lwjgl3ify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.slf4j.LoggerFactory;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

public class Helper {

    private static Map<Lwjgl3ApplicationConfiguration, Integer> width = new WeakHashMap<>();
    private static Map<Lwjgl3ApplicationConfiguration, Integer> height = new WeakHashMap<>();
    private static Map<Lwjgl3ApplicationConfiguration, List<String>> icons = new WeakHashMap<>();
    private static InputProcessor inputProcessor;

    public static void setWidth(Lwjgl3ApplicationConfiguration cfg, int width) {
        Integer height = Helper.height.remove(cfg);
        if (height != null) {
            cfg.setWindowedMode(width, height);
        } else {
            Helper.width.put(cfg, width);
        }
    }

    public static void setHeight(Lwjgl3ApplicationConfiguration cfg, int height) {
        Integer width = Helper.height.remove(cfg);
        if (width != null) {
            cfg.setWindowedMode(width, height);
        } else {
            Helper.height.put(cfg, height);
        }
    }

    public static void addIcon(Lwjgl3ApplicationConfiguration cfg, String path, FileType type) {
        if (type != FileType.Internal) {
            throw new IllegalStateException("Non-internal file types are not supported by the LWJGL helper!");
        }
        List<String> list = Helper.icons.get(cfg);
        if (list == null) {
            list = new ArrayList<>();
            Helper.icons.put(cfg, list);
        }
        list.add(path);
        cfg.setWindowIcon(list.toArray(new String[0]));
    }

    public static void setInputProcessor(Input input, InputProcessor processor) {
        input.setInputProcessor(processor);
        Helper.inputProcessor = processor;
    }

    public static void getInputProcessor(Input input) {
        if (input.getInputProcessor() != Helper.inputProcessor) {
            LoggerFactory.getLogger(Helper.class).info("The LWJGL3ify Helper class disagrees with libGDX about the state of the input processor.");
        }
    }
}
