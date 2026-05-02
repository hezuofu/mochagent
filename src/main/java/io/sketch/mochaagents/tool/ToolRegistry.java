package io.sketch.mochaagents.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表.
 */
public class ToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) { tools.put(tool.getName(), tool); }
    public Tool get(String name) { return tools.get(name); }
    public boolean has(String name) { return tools.containsKey(name); }
    public java.util.Collection<Tool> all() { return tools.values(); }
}
