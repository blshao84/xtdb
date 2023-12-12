package xtdb.query;

import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import xtdb.IllegalArgumentException;

import java.io.IOException;

public class UnnestColDeserializer extends StdDeserializer<Query.UnnestCol> {

    public UnnestColDeserializer() {
        super(Query.UnnestCol.class);
    }

    @Override
    public Query.UnnestCol deserialize(JsonParser p, DeserializationContext ctxt) throws IllegalArgumentException, IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode node = mapper.readTree(p);

        try {
            JsonNode unnest = node.get("unnest");
            if (unnest.isObject() && unnest.size() == 1) {
                ColSpec colSpec = mapper.treeToValue(unnest, ColSpec.class);
                return Query.unnestCol(colSpec);
            } else {
                throw new Exception("Unnest should be an object with only a single binding");
            }
        } catch (Exception e) {
            throw IllegalArgumentException.create(Keyword.intern("xtdb", "malformed-unnest"), PersistentHashMap.create(Keyword.intern("json"), node.toPrettyString()), e);
        }
    }
}
