package com.wsteam.wandscape.core.task;

import java.util.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BlueprintInterpreter}.
 */
@DisplayName("BlueprintInterpreter")
class BlueprintInterpreterTest {

    private BlueprintRegistry registry;
    private BlueprintInterpreter interpreter;

    @BeforeEach
    void setUp() {
        registry = new BlueprintRegistry();
        interpreter = new BlueprintInterpreter(registry);
    }

    // ── Helpers ──

    private Map<String, JsonElement> params() {
        Map<String, JsonElement> p = new HashMap<>();
        p.put("anchor", posArray(0, 64, 0));
        return p;
    }

    private static JsonArray posArray(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    private static Map<String, JsonElement> p(String k, JsonElement v) { return Map.of(k, v); }

    // ─────────────────────────────────────────────────────
    // Expression evaluation
    // ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Expression evaluation")
    class ExpressionTests {

        @Test
        @DisplayName("literal string")
        void literalString() {
            ExprNode expr = new ExprNode.LiteralString("minecraft:stone");
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertTrue(result.isJsonPrimitive());
            assertEquals("minecraft:stone", result.getAsString());
        }

        @Test
        @DisplayName("literal int")
        void literalInt() {
            ExprNode expr = new ExprNode.LiteralInt(42);
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertEquals(42, result.getAsInt());
        }

        @Test
        @DisplayName("literal pos")
        void literalPos() {
            ExprNode expr = new ExprNode.LiteralPos(new GridPos(1, 2, 3));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertTrue(result.isJsonArray());
            assertEquals("[1,2,3]", result.toString());
        }

        @Test
        @DisplayName("variable reference")
        void varReference() {
            ExprNode expr = new ExprNode.Var("test");
            JsonElement result = interpreter.evaluate(expr, p("test", new JsonPrimitive("hello")));
            assertEquals("hello", result.getAsString());
        }

        @Test
        @DisplayName("undefined variable throws")
        void undefinedVar() {
            ExprNode expr = new ExprNode.Var("missing");
            assertThrows(BlueprintInterpreter.BlueprintInterpretException.class,
                    () -> interpreter.evaluate(expr, Collections.emptyMap()));
        }

        @Test
        @DisplayName("add: pos + pos")
        void addPosPos() {
            ExprNode expr = new ExprNode.Add(
                    new ExprNode.LiteralPos(new GridPos(1, 2, 3)),
                    new ExprNode.LiteralPos(new GridPos(4, 5, 6)));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertTrue(result.isJsonArray());
            assertEquals("[5,7,9]", result.toString());
        }

        @Test
        @DisplayName("add: int + int")
        void addIntInt() {
            ExprNode expr = new ExprNode.Add(
                    new ExprNode.LiteralInt(10),
                    new ExprNode.LiteralInt(20));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertEquals(30, result.getAsInt());
        }

        @Test
        @DisplayName("sub: int - int")
        void subIntInt() {
            ExprNode expr = new ExprNode.Sub(
                    new ExprNode.LiteralInt(10),
                    new ExprNode.LiteralInt(3));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertEquals(7, result.getAsInt());
        }

        @Test
        @DisplayName("mul: int * int")
        void mulIntInt() {
            ExprNode expr = new ExprNode.Mul(
                    new ExprNode.LiteralInt(5),
                    new ExprNode.LiteralInt(6));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertEquals(30, result.getAsInt());
        }

        @Test
        @DisplayName("eq: equal strings")
        void eqTrue() {
            ExprNode expr = new ExprNode.Eq(
                    new ExprNode.LiteralString("abc"),
                    new ExprNode.LiteralString("abc"));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertTrue(result.getAsBoolean());
        }

        @Test
        @DisplayName("eq: unequal ints")
        void eqFalse() {
            ExprNode expr = new ExprNode.Eq(
                    new ExprNode.LiteralInt(1),
                    new ExprNode.LiteralInt(2));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertFalse(result.getAsBoolean());
        }

        @Test
        @DisplayName("size of list")
        void sizeOfList() {
            ExprNode expr = new ExprNode.Size(
                    new ExprNode.LiteralListString(List.of("a", "b", "c")));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertEquals(3, result.getAsInt());
        }

        @Test
        @DisplayName("format")
        void format() {
            ExprNode expr = new ExprNode.Format(
                    new ExprNode.LiteralString("Build {} at {}"),
                    List.of(new ExprNode.LiteralString("House"), new ExprNode.LiteralString("(0,64,0)")));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertEquals("Build House at (0,64,0)", result.getAsString());
        }

        @Test
        @DisplayName("keyof pos")
        void keyOf() {
            ExprNode expr = new ExprNode.KeyOf(new ExprNode.LiteralPos(new GridPos(5, 6, 7)));
            JsonElement result = interpreter.evaluate(expr, Collections.emptyMap());
            assertEquals("5,6,7", result.getAsString());
        }

        @Test
        @DisplayName("map get with keyof")
        void mapGet() {
            JsonObject map = new JsonObject();
            map.addProperty("1,2,3", "minecraft:stone");
            map.addProperty("4,5,6", "minecraft:dirt");
            Map<String, JsonElement> ctx = Map.of("m", map);

            ExprNode expr = new ExprNode.MapGet(
                    new ExprNode.Var("m"),
                    new ExprNode.KeyOf(new ExprNode.LiteralPos(new GridPos(1, 2, 3))));
            JsonElement result = interpreter.evaluate(expr, ctx);
            assertEquals("minecraft:stone", result.getAsString());
        }
    }

    // ─────────────────────────────────────────────────────
    // Step expansion
    // ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Step expansion")
    class StepTests {

        @Test
        @DisplayName("place step generates TransformOp")
        void placeStep() {
            BlueprintDefinition def = new BlueprintDefinition("test:place", Collections.emptyMap(),
                    List.of(new StepNode.PlaceStep(
                            new ExprNode.LiteralPos(new GridPos(0, 64, 0)),
                            new ExprNode.LiteralString("minecraft:stone_bricks"))));

            TaskSequence seq = interpreter.interpret(def, params());
            assertEquals(1, seq.size());
            assertTrue(seq.get(0) instanceof AtomicOp.TransformOp);
            AtomicOp.TransformOp op = (AtomicOp.TransformOp) seq.get(0);
            assertEquals(new GridPos(0, 64, 0), op.target());
            assertEquals(new BlockType("minecraft:stone_bricks"), op.to());
        }

        @Test
        @DisplayName("emit_event generates EmitEventOp")
        void emitEventStep() {
            BlueprintDefinition def = new BlueprintDefinition("test:emit", Collections.emptyMap(),
                    List.of(new StepNode.EmitEventStep(
                            new ExprNode.LiteralString("test_event"),
                            Map.of("key", new ExprNode.LiteralString("value")))));

            TaskSequence seq = interpreter.interpret(def, Collections.emptyMap());
            assertEquals(1, seq.size());
            assertTrue(seq.get(0) instanceof AtomicOp.EmitEventOp);
            AtomicOp.EmitEventOp op = (AtomicOp.EmitEventOp) seq.get(0);
            assertEquals("test_event", op.eventName());
            assertEquals("value", op.templateParams().get("key"));
        }

        @Test
        @DisplayName("request_resource generates ResourceRequestOp")
        void requestResourceStep() {
            BlueprintDefinition def = new BlueprintDefinition("test:req", Collections.emptyMap(),
                    List.of(new StepNode.RequestResourceStep(
                            new ExprNode.LiteralString("wood"),
                            new ExprNode.LiteralInt(10))));

            TaskSequence seq = interpreter.interpret(def, Collections.emptyMap());
            assertEquals(1, seq.size());
            assertTrue(seq.get(0) instanceof AtomicOp.ResourceRequestOp);
            AtomicOp.ResourceRequestOp op = (AtomicOp.ResourceRequestOp) seq.get(0);
            assertEquals("wood", op.requested().resource().id());
            assertEquals(10, op.requested().amount());
        }

        @Test
        @DisplayName("for_each expands once per element")
        void forEachStep() {
            BlueprintDefinition def = new BlueprintDefinition("test:foreach", Collections.emptyMap(),
                    List.of(new StepNode.ForEachStep(
                            new ExprNode.LiteralListPos(List.of(
                                    new GridPos(0, 0, 0), new GridPos(1, 0, 0), new GridPos(0, 1, 0))),
                            "off",
                            List.of(new StepNode.PlaceStep(
                                    new ExprNode.Var("off"),
                                    new ExprNode.LiteralString("minecraft:stone"))))));

            Map<String, JsonElement> p = params();
            TaskSequence seq = interpreter.interpret(def, p);
            assertEquals(3, seq.size());
            assertEquals(new GridPos(0, 0, 0), ((AtomicOp.TransformOp) seq.get(0)).target());
            assertEquals(new GridPos(1, 0, 0), ((AtomicOp.TransformOp) seq.get(1)).target());
            assertEquals(new GridPos(0, 1, 0), ((AtomicOp.TransformOp) seq.get(2)).target());
        }

        @Test
        @DisplayName("for_each shadowing detection")
        void forEachShadowing() {
            // Declare a param named "off", then try to use it as loop var
            Map<String, ParamType> params = new LinkedHashMap<>();
            params.put("off", ParamType.POS);
            BlueprintDefinition def = new BlueprintDefinition("test:shadow", params,
                    List.of(new StepNode.ForEachStep(
                            new ExprNode.LiteralListPos(List.of(new GridPos(0, 0, 0))),
                            "off",  // shadows param "off"
                            List.of(new StepNode.PlaceStep(
                                    new ExprNode.Var("off"),
                                    new ExprNode.LiteralString("minecraft:stone"))))));

            Map<String, JsonElement> p = new HashMap<>();
            p.put("off", posArray(0, 64, 0));

            assertThrows(BlueprintInterpreter.BlueprintInterpretException.class,
                    () -> interpreter.interpret(def, p));
        }

        @Test
        @DisplayName("if step generates IfConditionOp with correct then/else structure")
        void ifStep() {
            // Use real Ops so the expansion is visible
            ExprNode pos = new ExprNode.LiteralPos(new GridPos(0, 64, 0));
            StepNode.IfStep ifStep = new StepNode.IfStep(
                    new ExprNode.LiteralString("resource_below"),
                    Map.of("resource", new ExprNode.LiteralString("wood"),
                           "threshold", new ExprNode.LiteralInt(10)),
                    false,
                    List.of(new StepNode.PlaceStep(pos,
                            new ExprNode.LiteralString("minecraft:dirt"))),
                    List.of(new StepNode.PlaceStep(pos,
                            new ExprNode.LiteralString("minecraft:stone"))));

            BlueprintDefinition def = new BlueprintDefinition("test:if",
                    Collections.emptyMap(), List.of(ifStep));

            TaskSequence seq = interpreter.interpret(def, Collections.emptyMap());
            // Structure: [then_place] + IfConditionOp(skip=1) + [else_place]
            assertEquals(3, seq.size());
            assertTrue(seq.get(1) instanceof AtomicOp.IfConditionOp);
            AtomicOp.IfConditionOp ifOp = (AtomicOp.IfConditionOp) seq.get(1);
            assertEquals("resource_below", ifOp.conditionName());
            assertEquals("wood", ifOp.params().get("resource"));
            assertEquals("10", ifOp.params().get("threshold"));
            assertEquals(1, ifOp.skipCount(), "skipCount = elseOps.size() = 1");
            assertFalse(ifOp.elseSkip());
        }

        @Test
        @DisplayName("log step generates no AtomicOp")
        void logStep() {
            BlueprintDefinition def = new BlueprintDefinition("test:log", Collections.emptyMap(),
                    List.of(new StepNode.LogStep("info",
                            new ExprNode.LiteralString("hello world"))));

            TaskSequence seq = interpreter.interpret(def, Collections.emptyMap());
            assertEquals(0, seq.size(), "log step should produce no AtomicOps");
        }

        @Test
        @DisplayName("label includes display name and anchor")
        void labelFromDisplayName() {
            BlueprintDefinition def = new BlueprintDefinition("test", Collections.emptyMap(),
                    List.of(), "Build House", "");
            Map<String, JsonElement> p = params(); // anchor = [0,64,0]
            TaskSequence seq = interpreter.interpret(def, p);
            assertTrue(seq.label().contains("Build House"));
            assertTrue(seq.label().contains("(0, 64, 0)"));
        }
    }

    // ─────────────────────────────────────────────────────
    // Param validation
    // ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Param validation")
    class ParamValidationTests {

        @Test
        @DisplayName("missing required param throws")
        void missingRequiredParam() {
            Map<String, ParamType> params = new LinkedHashMap<>();
            params.put("offsets", ParamType.LIST_POS);
            params.put("blocks", ParamType.MAP_STRING_STRING);
            BlueprintDefinition def = new BlueprintDefinition("test:need", params, List.of());

            assertThrows(BlueprintInterpreter.BlueprintInterpretException.class,
                    () -> interpreter.interpret(def, Collections.emptyMap()));
        }

        @Test
        @DisplayName("extra params allowed (ignored)")
        void extraParamsAllowed() {
            BlueprintDefinition def = new BlueprintDefinition("test:opt", Collections.emptyMap(),
                    List.of(new StepNode.LogStep("info", new ExprNode.LiteralString("ok"))));
            Map<String, JsonElement> p = new HashMap<>();
            p.put("extra", new JsonPrimitive("ignored"));
            assertDoesNotThrow(() -> interpreter.interpret(def, p));
        }
    }

    // ─────────────────────────────────────────────────────
    // Recursion detection
    // ─────────────────────────────────────────────────────

    @Test
    @DisplayName("call recursion detection")
    void callRecursionDetection() {
        // Register a blueprint that calls itself
        BlueprintDefinition selfCall = new BlueprintDefinition("recursive:self",
                Collections.emptyMap(),
                List.of(new StepNode.CallStep(
                        new ExprNode.LiteralString("recursive:self"),
                        Collections.emptyMap())));

        registry.register("recursive:self", new Blueprint("recursive:self",
                (BlueprintSteps) p -> interpreter.interpret(selfCall, p)));

        assertThrows(BlueprintInterpreter.BlueprintInterpretException.class,
                () -> interpreter.interpret(selfCall, Collections.emptyMap()));
    }
}
