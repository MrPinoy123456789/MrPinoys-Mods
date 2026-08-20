package kamutotems;

import kamutotems.core.Kamu;
import kamutotems.core.KamuCatalog;
import kamutotems.core.Polarity;

import java.io.StringReader;
import java.io.StringWriter;

/** Regression coverage for the operator-editable component catalog schema. */
public final class KamuDataRegressionTest {

    public static void main(String[] args) {
        KamuCatalog defaults = KamuCatalog.defaults();
        StringWriter json = new StringWriter();
        KamuData.writeCatalog(json, defaults);

        KamuCatalog reloaded = KamuData.readCatalog(new StringReader(json.toString()));
        for (Kamu original : defaults.all()) {
            Kamu copy = reloaded.get(original.id());
            if (copy == null || copy.polarity() != original.polarity()) {
                throw new AssertionError("polarity did not round-trip for " + original.id());
            }
        }
        if (reloaded.auraModifiers().size() != defaults.auraModifiers().size()) {
            throw new AssertionError("round-trip changed the aura-capable catalog");
        }

        String legacy = """
                {"kamu":[{
                  "id":"legacy","displayName":"Legacy","category":"ELEMENT",
                  "rarity":"COMMON","complexity":1,"effectId":"fire",
                  "parameters":{},"tags":[],"allowedHosts":["TOTEM"]
                }]}
                """;
        Kamu legacyKamu = KamuData.readCatalog(new StringReader(legacy)).get("legacy");
        if (legacyKamu == null || legacyKamu.polarity() != Polarity.NONE) {
            throw new AssertionError("legacy catalog entry must default polarity to NONE");
        }

        System.out.println("KamuDataRegressionTest passed");
    }
}
