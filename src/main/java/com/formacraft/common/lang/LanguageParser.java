package com.formacraft.common.lang;

public class LanguageParser {
    public static StructureData parse(String description) {
        // TODO: integrate LLM API to extract structure metadata
        return new StructureData("castle", "stone", 2, "medieval", 0, 0, 0);
    }
}
