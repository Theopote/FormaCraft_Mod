# JSON library policy

**Preferred for new Java code:** `com.formacraft.common.json.JsonUtil` (Gson).

Jackson remains in some network/repair paths (e.g. FBD repair). Do not introduce a third library.
New call sites should use JsonUtil unless an existing Jackson ObjectMapper pipeline is being extended in-place.
