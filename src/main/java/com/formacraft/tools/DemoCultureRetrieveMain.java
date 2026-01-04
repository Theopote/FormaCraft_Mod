package com.formacraft.tools;

import com.formacraft.server.rag.CultureCardRepository;
import com.formacraft.server.rag.KeywordCultureRetriever;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Quick CLI demo:
 *   gradlew demoCultureRetrieve --args="生成一个哥特式大教堂 玫瑰花窗 飞扶壁"
 */
public final class DemoCultureRetrieveMain {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        String prompt = (args != null && args.length >= 1) ? args[0] : "";
        int topK = (args != null && args.length >= 2) ? tryInt(args[1], 3) : 3;
        int few = (args != null && args.length >= 3) ? tryInt(args[2], 2) : 2;

        CultureCardRepository repo = CultureCardRepository.load();
        KeywordCultureRetriever ret = new KeywordCultureRetriever(repo);
        KeywordCultureRetriever.RetrievalResult res = ret.retrieve(prompt, topK, few);
        System.out.println(GSON.toJson(res));
    }

    private static int tryInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { return def; }
    }
}


