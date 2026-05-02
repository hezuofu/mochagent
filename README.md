# smolagents-java

Java implementation of smolagents - a lightweight AI agent framework.

## Features

- **Code Agent**: Write actions as Java code for improved performance
- **Tool Calling Agent**: Use JSON format for traditional tool calls
- **Model Agnostic**: Support for multiple LLM backends (OpenAI, LiteLLM, etc.)
- **Type Safe**: Leverage Java's type system for safety
- **Async Support**: Built on Java 21 Virtual Threads
- **Modular Design**: Easy to extend and customize

## Quick Start

### Add Dependency

```xml
<dependency>
    <groupId>com.smolagents</groupId>
    <artifactId>smolagents-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Create and Run Agent

```java
import io.sketch.mochaagents.agents.CodeAgent;
import io.sketch.mochaagents.models.OpenAIModel;
import io.sketch.mochaagents.tools.defaults.WebSearchTool;

public class Main {
    public static void main(String[] args) {
        // Create model
        Model model = new OpenAIModel("gpt-4o", System.getenv("OPENAI_API_KEY"));

        // Create agent
        CodeAgent agent = CodeAgent.builder()
                .model(model)
                .tools(List.of(new WebSearchTool()))
                .maxSteps(20)
                .build();

        // Run task
        Object result = agent.run("What is AI?");
        System.out.println(result);
    }
}
```

## Modules

- `agents` - Core agent implementations (CodeAgent, ToolCallingAgent)
- `tools` - Tool abstraction and default tools
- `models` - LLM model interface and implementations
- `memory` - Memory system for tracking execution steps
- `executor` - Java code executor
- `monitoring` - Logging and monitoring
- `types` - Multi-modal types (AgentImage, AgentAudio)

## Requirements

- Java 21+
- Maven 3.9+

## License

MIT License