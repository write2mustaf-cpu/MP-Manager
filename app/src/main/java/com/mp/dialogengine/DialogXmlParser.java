package com.mp.dialogengine;

import com.mp.dialogengine.DialogModels.Action;
import com.mp.dialogengine.DialogModels.Event;
import com.mp.dialogengine.DialogModels.Model;
import com.mp.dialogengine.DialogModels.Node;
import com.mp.dialogengine.DialogModels.SourceRef;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Pure XML-to-model conversion. No Android or dex type is referenced here. */
public final class DialogXmlParser {

    public interface Resolver {
        String resolve(String value);
    }

    private DialogXmlParser() {
    }

    public static Model parse(String xml, String fileName, String sourcePath,
                              String container, Resolver resolver) {
        Model model = new Model();
        model.name = stripExtension(fileName);
        model.metadata.put("extracted", "true");
        SourceRef ref = new SourceRef();
        ref.kind = "xml";
        ref.container = container == null ? fileName : container;
        ref.path = sourcePath == null ? "" : sourcePath;
        ref.snippet = xml.length() > 600 ? xml.substring(0, 600) : xml;
        ref.confidence = 0.9f;
        model.sourceRef = ref;
        try {
            Document doc = parseDoc(xml);
            if (doc == null) return model;
            Element root = doc.getDocumentElement();
            if (root == null) return model;
            model.title = textOf(root, "title", resolver);
            model.message = textOf(root, "message", resolver);
            collectInto(root, model.nodes, model, resolver, 0);
            if (model.title.isEmpty()) {
                for (Node n : model.nodes) {
                    if ("title".equals(n.kind)) {
                        model.title = n.text;
                        model.nodes.remove(n);
                        break;
                    }
                }
            }
            if (model.message.isEmpty()) {
                for (Node n : model.nodes) {
                    if ("message".equals(n.kind)) {
                        model.message = n.text;
                        model.nodes.remove(n);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return model;
    }

    public static boolean looksLikeDialog(String xml, String fileName) {
        if (xml == null || xml.isEmpty()) return false;
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("dialog") || lower.contains("alert") || lower.contains("sheet")
                || lower.contains("popup")) {
            return true;
        }
        String l = xml.toLowerCase(Locale.ROOT);
        return l.contains("windowisfloating") || l.contains("windowdimamount")
                || l.contains("@android:id/message") || l.contains("@android:id/button1")
                || l.contains("@android:id/button2") || l.contains("@android:id/button3")
                || l.contains("<alertdialog") || l.contains("<dialog")
                || l.contains("bottomsheet") || l.contains("dialogfragment");
    }

    private static void collectInto(Element element, List<Node> out, Model model,
                                    Resolver resolver, int depth) {
        if (element == null || depth > 30) return;
        String tag = element.getTagName() == null ? "" : element.getTagName();
        String simple = tag.contains(".") ? tag.substring(tag.lastIndexOf('.') + 1) : tag;
        String id = idOf(attr(element, "id"));
        String onClick = attr(element, "onClick");
        NodeList children = element.getChildNodes();
        boolean hasElementChildren = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                hasElementChildren = true;
                break;
            }
        }

        if ("title".equals(id)) {
            Node n = textNode(element, "title", resolver);
            if (n != null && !n.text.isEmpty()) out.add(n);
            return;
        }
        if ("message".equals(id)) {
            Node n = textNode(element, "message", resolver);
            if (n != null && !n.text.isEmpty()) out.add(n);
            return;
        }

        if (isContainer(simple)) {
            if (depth == 0) {
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element child) {
                        collectInto(child, out, model, resolver, depth + 1);
                    }
                }
                return;
            }
            String bgColor = hex(resolver == null ? attr(element, "background")
                    : resolver.resolve(attr(element, "background")));
            if (hasElementChildren && bgColor != null && !bgColor.isEmpty()) {
                Node card = new Node();
                card.kind = "card";
                card.id = id.isEmpty() ? DialogModels.newId("node") : id;
                card.backgroundColor = bgColor;
                card.cornerRadiusDp = dp(attr(element, "radius"), 0f);
                card.paddingDp = dp(attr(element, "padding"), 8f);
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element child) {
                        collectInto(child, card.children, model, resolver, depth + 1);
                    }
                }
                out.add(card);
                return;
            }
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element child) {
                    collectInto(child, out, model, resolver, depth + 1);
                }
            }
            return;
        }

        Node leaf = leafNode(element, simple, id, resolver);
        if (leaf != null) {
            if ("button".equals(leaf.kind) || !onClick.isEmpty()) {
                attachClick(model, leaf, onClick);
            }
            out.add(leaf);
        }
    }

    private static Node leafNode(Element e, String simple, String id, Resolver resolver) {
        String s = simple.toLowerCase(Locale.ROOT);
        Node n = new Node();
        n.id = id.isEmpty() ? DialogModels.newId("node") : id;
        n.text = textOf(e, "text", resolver);
        n.hint = textOf(e, "hint", resolver);
        n.textSizeSp = sp(attr(e, "textSize"), 15f);
        n.textColor = hex(resolver.resolve(attr(e, "textColor")));
        n.backgroundColor = hex(resolver.resolve(attr(e, "background")));
        n.cornerRadiusDp = dp(attr(e, "radius"), 0f);
        n.paddingDp = dp(attr(e, "padding"), 0f);
        n.widthDp = dimen(attr(e, "layout_width"));
        n.heightDp = dimen(attr(e, "layout_height"));
        n.gravity = gravity(attr(e, "gravity"));
        n.visible = !"gone".equalsIgnoreCase(attr(e, "visibility"))
                && !"invisible".equalsIgnoreCase(attr(e, "visibility"));
        n.enabled = !"false".equalsIgnoreCase(attr(e, "enabled"));
        n.checked = "true".equalsIgnoreCase(attr(e, "checked"));
        if (s.contains("button")) {
            n.kind = "button";
            if (n.text.isEmpty() && id.startsWith("button")) n.text = defaultButton(id);
        } else if (s.contains("edittext") || s.contains("textinputedittext")) {
            n.kind = "input";
            String input = attr(e, "inputType");
            if (input.contains("number")) n.tag = "number";
            else if (input.contains("password")) n.tag = "password";
            else if (input.contains("phone")) n.tag = "phone";
            else if (input.contains("textEmail")) n.tag = "email";
        } else if (s.contains("checkbox") || s.contains("switch")
                || s.contains("togglebutton") || s.contains("radiobutton")) {
            n.kind = "toggle";
        } else if (s.contains("imageview") || s.contains("imagebutton")) {
            n.kind = "image";
            n.text = "";
        } else if (s.contains("progressbar") || s.contains("seekbar")) {
            n.kind = "progress";
            n.indeterminate = true;
        } else if (s.contains("listview") || s.contains("recyclerview")
                || s.contains("spinner") || s.contains("gridview")) {
            n.kind = "list";
            n.searchable = true;
        } else if (s.contains("textview") || s.contains("textclock")
                || s.contains("chronometer")) {
            n.kind = "text";
            if ("title".equals(id)) n.kind = "title";
            else if ("message".equals(id)) n.kind = "message";
        } else if (s.contains("space")) {
            n.kind = "spacer";
        } else if (s.equals("view")) {
            n.kind = "divider";
            n.heightDp = 1;
        } else {
            return null;
        }
        if (n.text.isEmpty() && !"button".equals(n.kind)) {
            if ("text".equals(n.kind) || "title".equals(n.kind) || "message".equals(n.kind)) {
                // Empty TextView is often a spacer; keep only if it has a background.
                if (n.backgroundColor.isEmpty()) return null;
            }
        }
        return n;
    }

    private static void attachClick(Model model, Node node, String onClick) {
        Event e = new Event();
        e.trigger = "click";
        e.nodeId = node.id;
        e.priority = 0;
        e.stopOnFailure = true;
        Action dismiss = new Action();
        dismiss.type = "dismiss";
        dismiss.stopOnFailure = false;
        Action toast = null;
        if (onClick != null && !onClick.isEmpty()) {
            toast = new Action();
            toast.type = "toast";
            toast.value = "onClick: " + onClick;
            toast.stopOnFailure = false;
        }
        if (toast != null) e.actions.add(toast);
        e.actions.add(dismiss);
        model.events.add(e);
        node.eventIds.add(e.id);
        model.metadata.put("events_extracted", "true");
    }

    private static Node textNode(Element e, String kind, Resolver resolver) {
        String text = textOf(e, "text", resolver);
        if (text.isEmpty()) return null;
        Node n = new Node();
        n.kind = kind;
        n.id = idOf(attr(e, "id"));
        if (n.id.isEmpty()) n.id = DialogModels.newId("node");
        n.text = text;
        n.textSizeSp = sp(attr(e, "textSize"), "title".equals(kind) ? 18f : 14f);
        n.textColor = hex(resolver.resolve(attr(e, "textColor")));
        n.gravity = gravity(attr(e, "gravity"));
        return n;
    }

    private static String textOf(Element e, String name, Resolver resolver) {
        String raw = attr(e, name);
        if (raw.isEmpty()) return "";
        String v = resolver == null ? raw : resolver.resolve(raw);
        if (v == null) return "";
        if (v.startsWith("@") && resolver != null) {
            String r = resolver.resolve(v);
            if (r != null && !r.startsWith("@")) v = r;
        }
        return v;
    }

    private static String attr(Element e, String name) {
        if (e == null || name == null) return "";
        String v = e.getAttribute("android:" + name);
        if (v != null && !v.isEmpty()) return v;
        v = e.getAttribute(name);
        if (v != null && !v.isEmpty()) return v;
        NamedNodeMap map = e.getAttributes();
        if (map != null) {
            for (int i = 0; i < map.getLength(); i++) {
                org.w3c.dom.Node a = map.item(i);
                String ln = a.getLocalName();
                if (ln == null) {
                    String nn = a.getNodeName();
                    if (nn != null && nn.contains(":")) ln = nn.substring(nn.indexOf(':') + 1);
                    else ln = nn;
                }
                if (name.equals(ln)) return a.getNodeValue() == null ? "" : a.getNodeValue();
            }
        }
        return "";
    }

    private static Document parseDoc(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Throwable ignored) {
        }
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new org.xml.sax.InputSource(new StringReader(xml)));
    }

    private static String idOf(String value) {
        if (value == null) return "";
        String v = value;
        if (v.startsWith("@+id/")) v = v.substring(5);
        else if (v.startsWith("@id/")) v = v.substring(4);
        else if (v.startsWith("@android:id/")) v = v.substring(12);
        return v;
    }

    private static String defaultButton(String id) {
        if ("button1".equals(id)) return "OK";
        if ("button2".equals(id)) return "Cancel";
        return "Button";
    }

    private static boolean isContainer(String simple) {
        String s = simple.toLowerCase(Locale.ROOT);
        return s.contains("layout") || s.contains("scrollview") || s.contains("cardview")
                || s.contains("viewgroup") || s.contains("constraint") || s.contains("toolbar")
                || s.contains("viewpager") || s.contains("frame");
    }

    private static int dimen(String value) {
        if (value == null || value.isEmpty()) return -2;
        if ("match_parent".equals(value) || "fill_parent".equals(value)) return -1;
        if ("wrap_content".equals(value)) return -2;
        return (int) dp(value, 0f);
    }

    private static float dp(String value, float fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.endsWith("dp") || v.endsWith("dip")) v = v.substring(0, v.length() - 2);
            else if (v.endsWith("px")) v = v.substring(0, v.length() - 2);
            return Float.parseFloat(v.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float sp(String value, float fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.endsWith("sp")) v = v.substring(0, v.length() - 2);
            return Float.parseFloat(v.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String hex(String value) {
        if (value == null || value.isEmpty()) return "";
        String v = value.trim();
        if (v.startsWith("#")) return v;
        return "";
    }

    private static String gravity(String value) {
        if (value == null) return "start";
        String v = value.toLowerCase(Locale.ROOT);
        if (v.contains("center")) return "center";
        if (v.contains("end") || v.contains("right")) return "end";
        return "start";
    }

    private static String stripExtension(String name) {
        if (name == null) return "dialog";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
