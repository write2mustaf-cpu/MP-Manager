package com.mp.dialogengine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.mp.dialogengine.DialogModels.Action;
import com.mp.dialogengine.DialogModels.Event;
import com.mp.dialogengine.DialogModels.Finding;
import com.mp.dialogengine.DialogModels.FunctionRef;
import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Node;
import com.mp.dialogengine.DialogModels.Project;

import org.junit.Test;

import java.util.List;

public class DialogEngineTest {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\">\n"
            + "  <TextView android:id=\"@+id/title\" android:text=\"Hello\"\n"
            + "      android:textSize=\"18sp\" android:textColor=\"#FF112233\" />\n"
            + "  <TextView android:id=\"@+id/message\" android:text=\"World\" />\n"
            + "  <Button android:id=\"@+id/button1\" android:text=\"OK\"\n"
            + "      android:onClick=\"onOk\" />\n"
            + "</LinearLayout>";

    @Test
    public void parsesAxmlishDialogXml() {
        Model model = DialogXmlParser.parse(XML, "dialog_test.xml", "/tmp/x.xml",
                "res/layout/dialog_test.xml", value -> value);
        assertEquals("Hello", model.title);
        assertEquals("World", model.message);
        assertNotNull(model.nodes);
        assertTrue(model.nodes.size() >= 1);
        boolean button = false;
        for (Node n : model.nodes) {
            if ("button".equals(n.kind)) button = true;
        }
        assertTrue(button);
        assertFalse(model.events.isEmpty());
    }

    @Test
    public void detectsDuplicateNodeIds() {
        Project project = new Project();
        Model model = new Model();
        Node a = new Node();
        a.id = "dup";
        a.kind = "text";
        a.text = "A";
        Node b = new Node();
        b.id = "dup";
        b.kind = "text";
        b.text = "B";
        model.nodes.add(a);
        model.nodes.add(b);
        List<Finding> findings = DialogAnalyzer.analyze(project, model);
        boolean found = false;
        for (Finding f : findings) if ("duplicate_node_id".equals(f.type)) found = true;
        assertTrue(found);
    }

    @Test
    public void safeFixRenamesDuplicatesAndKeepsEventBinding() {
        Project project = new Project();
        Model model = new Model();
        Node a = new Node();
        a.id = "same";
        a.kind = "button";
        a.text = "A";
        Node b = new Node();
        b.id = "same";
        b.kind = "text";
        b.text = "B";
        model.nodes.add(a);
        model.nodes.add(b);
        Event e = new Event();
        e.id = "e1";
        e.trigger = "click";
        e.nodeId = "same";
        Action action = new Action();
        action.type = "dismiss";
        e.actions.add(action);
        model.events.add(e);
        List<Finding> findings = DialogAnalyzer.analyze(project, model);
        int changes = DialogAnalyzer.applySafeFixes(project, model, findings);
        assertTrue(changes > 0);
        assertEquals(2, model.nodes.size());
        assertFalse(model.nodes.get(0).id.equals(model.nodes.get(1).id));
        assertNotNull(model.node(e.nodeId));
    }

    @Test
    public void mergeRemapsCollidingNodeIds() {
        Model a = new Model();
        a.name = "A";
        Node n1 = new Node();
        n1.id = "same";
        n1.kind = "text";
        n1.text = "A";
        a.nodes.add(n1);
        Model b = new Model();
        b.name = "B";
        Node n2 = new Node();
        n2.id = "same";
        n2.kind = "text";
        n2.text = "B";
        b.nodes.add(n2);
        DialogLibrary.MergeResult result = DialogLibrary.merge(a, b);
        assertNotNull(result.model);
        assertEquals(2, result.model.nodes.size());
        assertFalse(result.model.nodes.get(0).id.equals(result.model.nodes.get(1).id));
        assertNotNull(result.model.metadata.get("merged_from"));
    }

    @Test
    public void simulatorAcceptsSimpleDismissButton() {
        Project project = new Project();
        Model model = new Model();
        Node button = new Node();
        button.kind = "button";
        button.text = "OK";
        model.nodes.add(button);
        Event e = new Event();
        e.trigger = "click";
        e.nodeId = button.id;
        Action a = new Action();
        a.type = "dismiss";
        e.actions.add(a);
        model.events.add(e);
        DialogSimulator.Report report = DialogSimulator.simulate(project, model);
        assertTrue(report.passed > 0);
        assertEquals(0, report.failed);
    }

    @Test
    public void reconnectFunctionsBindsOnlyUniqueSafeMatches() {
        Project project = new Project();
        FunctionRef f = new FunctionRef();
        f.id = "safe1";
        f.methodName = "doThing";
        f.displayName = "Do thing";
        f.safe = true;
        project.functions.add(f);
        Model model = new Model();
        Event e = new Event();
        e.trigger = "click";
        Action a = new Action();
        a.type = "call_function";
        a.functionRef = "";
        a.value = "com.example.Helper#doThing()";
        e.actions.add(a);
        model.events.add(e);
        assertEquals(1, DialogAnalyzer.reconnectFunctions(project, model));
        assertEquals("safe1", a.functionRef);
    }

    @Test
    public void specContainsFieldsReadByRuntime() {
        Project project = new Project();
        FunctionRef f = new FunctionRef();
        f.id = "f1";
        f.displayName = "openSettings";
        f.safe = true;
        project.functions.add(f);
        Model model = new Model();
        model.title = "Hello";
        model.nodes.add(new Node());
        Event e = new Event();
        e.id = "e1";
        e.trigger = "click";
        Action a = new Action();
        a.type = "call_function";
        a.functionRef = "f1";
        e.actions.add(a);
        model.events.add(e);
        String json = DialogRuntimeInjector.buildSpec(project, model);
        com.google.gson.JsonObject spec =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertTrue(spec.has("title"));
        assertTrue(spec.has("nodes"));
        assertTrue(spec.has("events"));
        assertTrue(spec.has("functions"));
        assertTrue(spec.has("strings"));
        assertEquals(1, spec.getAsJsonArray("functions").size());
        assertEquals("f1", spec.getAsJsonArray("functions").get(0)
                .getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void nestedChildrenSurviveJsonRoundTrip() {
        Model model = new Model();
        Node card = new Node();
        card.kind = "card";
        Node title = new Node();
        title.kind = "title";
        title.text = "Inside";
        card.children.add(title);
        model.nodes.add(card);
        Model back = DialogJson.fromJson(DialogJson.toJson(model), Model.class);
        assertNotNull(back);
        assertEquals(1, back.nodes.size());
        assertEquals(1, back.nodes.get(0).children.size());
        assertEquals("Inside", back.nodes.get(0).children.get(0).text);
    }

    @Test
    public void specCarriesNestedDialogsForOpenById() {
        Project project = new Project();
        Model root = new Model();
        root.name = "Root";
        Model nested = new Model();
        nested.name = "Nested";
        nested.title = "Second";
        project.dialogs.add(root);
        project.dialogs.add(nested);
        Event e = new Event();
        e.trigger = "click";
        Action a = new Action();
        a.type = "open_dialog";
        a.target = nested.id;
        e.actions.add(a);
        root.events.add(e);
        com.google.gson.JsonObject spec = com.google.gson.JsonParser.parseString(
                DialogRuntimeInjector.buildSpec(project, root)).getAsJsonObject();
        assertTrue(spec.has("dialogs"));
        assertTrue(spec.getAsJsonObject("dialogs").has(nested.id));
        assertEquals("Second", spec.getAsJsonObject("dialogs").getAsJsonObject(nested.id)
                .get("title").getAsString());
    }

    @Test
    public void analyzerFindsDuplicateIdsInsideNestedChildren() {
        Project project = new Project();
        Model model = new Model();
        Node card = new Node();
        card.kind = "card";
        Node a = new Node();
        a.id = "dup";
        a.kind = "text";
        Node b = new Node();
        b.id = "dup";
        b.kind = "text";
        card.children.add(a);
        card.children.add(b);
        model.nodes.add(card);
        List<Finding> findings = DialogAnalyzer.analyze(project, model);
        boolean found = false;
        for (Finding f : findings) if ("duplicate_node_id".equals(f.type)) found = true;
        assertTrue(found);
    }

    @Test
    public void simulatorAcceptsNestedCardAndPages() {
        Project project = new Project();
        Model model = new Model();
        Node card = new Node();
        card.kind = "card";
        Node button = new Node();
        button.kind = "button";
        button.text = "OK";
        card.children.add(button);
        model.nodes.add(card);
        Event e = new Event();
        e.trigger = "click";
        e.nodeId = button.id;
        Action a = new Action();
        a.type = "dismiss";
        e.actions.add(a);
        model.events.add(e);
        DialogSimulator.Report report = DialogSimulator.simulate(project, model);
        assertEquals(0, report.failed);
    }

    @Test
    public void hasAtLeastTwentyDesigns() {
        assertTrue(DialogDesigns.all().size() >= 20);
    }

    @Test
    public void modelSurvivesGsonRoundTrip() {
        Model model = new Model();
        model.name = "Round";
        model.title = "Title";
        model.backgroundColor = "#CC112233";
        Node n = new Node();
        n.kind = "input";
        n.hint = "Type";
        n.dataBinding = "name";
        model.nodes.add(n);
        String json = DialogJson.toJson(model);
        Model back = DialogJson.fromJson(json, Model.class);
        assertNotNull(back);
        assertEquals("Round", back.name);
        assertEquals(1, back.nodes.size());
        assertEquals("name", back.nodes.get(0).dataBinding);
    }
}
