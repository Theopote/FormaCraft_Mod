# Gothic church optional elements

The native Gothic cathedral builder now accepts includeRoseWindow,
includeButtresses and includeTowers (Boolean or true/false strings); defaults stay
true. Disabling a rose preserves the underlying front wall. Disabling towers or
buttresses omits their geometry while retaining nave, aisle and entrance geometry.

The building contract recognizes exact Chinese negative phrases for 双塔,
玫瑰窗/玫瑰花窗 and 扶壁/飞扶壁, plus English no twin towers/no rose window/
no flying buttresses. It writes false only to one uniquely identified Gothic
STRUCTURE component, never an unrelated type. Named-building scopes and multiple
Gothic hosts are reported unresolved. This narrow binder is not a general
negation parser (quoted instructions or complex double negatives are unsupported).
It is not an edit command for existing world blocks.

Offline Python tests cover intent binding, unrelated/multiple scopes, English and
idempotence. Java final-state tests check absent towers/pier/rose glazing and a
retained floor-level portal-to-nave path. Default size/facing cases remain covered.
Material overrides, per-part architectural variants, precise historical fidelity,
world placement/terrain and LLM route game acceptance remain incomplete.

Game acceptance: build a Gothic church without twin towers, rose window or flying
buttresses; verify a taller central nave and lower side aisles remain, entrance is
walkable, and disabled features are absent. Update JAR and restart backend/game.
