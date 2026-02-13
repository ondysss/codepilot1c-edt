package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic metadata inspection node.
 */
public class MetadataNode {

    public enum FormatStyle {
        SIMPLE_VALUE,
        REFERENCE,
        EXPAND
    }

    private String name;
    private String type;
    private String path;
    private FormatStyle formatStyle = FormatStyle.EXPAND;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<MetadataNode> children = new ArrayList<>();

    public String getName() {
        return name;
    }

    public MetadataNode setName(String name) {
        this.name = name;
        return this;
    }

    public String getType() {
        return type;
    }

    public MetadataNode setType(String type) {
        this.type = type;
        return this;
    }

    public String getPath() {
        return path;
    }

    public MetadataNode setPath(String path) {
        this.path = path;
        return this;
    }

    public FormatStyle getFormatStyle() {
        return formatStyle;
    }

    public MetadataNode setFormatStyle(FormatStyle formatStyle) {
        this.formatStyle = formatStyle;
        return this;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public MetadataNode putProperty(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public List<MetadataNode> getChildren() {
        return children;
    }

    public MetadataNode addChild(MetadataNode child) {
        if (child != null) {
            children.add(child);
        }
        return this;
    }
}
