package io.sketch.mochaagents.agent.react;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plan mode state machine with 5-phase workflow — replicates claude-code's plan mode.
 *
 * <h2>5-Phase Workflow</h2>
 * <pre>
 *   Phase 1: UNDERSTAND — read code, ask questions, launch Explore agents
 *   Phase 2: DESIGN — launch Plan agents for design alternatives
 *   Phase 3: REVIEW — read critical files, resolve ambiguities
 *   Phase 4: FINAL_PLAN — write plan to disk with full detail
 *   Phase 5: EXIT — call exit for user approval
 * </pre>
 *
 * <h2>State Machine</h2>
 * <pre>
 *   DEFAULT → PLAN (read-only exploration)
 *   PLAN → APPROVED (write-enabled execution)
 *   PLAN → DEFAULT (user cancelled/rejected)
 * </pre>
 *
 * @author lanxia39@163.com
 */
public final class PlanMode {

    private static final Logger log = LoggerFactory.getLogger(PlanMode.class);

    public enum State { DEFAULT, PLAN, APPROVED }

    public enum Phase {
        UNDERSTAND("Read code, ask questions, explore the codebase"),
        DESIGN("Design alternative approaches, evaluate tradeoffs"),
        REVIEW("Read critical files, resolve ambiguities with user"),
        FINAL_PLAN("Write the complete plan to disk"),
        EXIT("Present plan for user approval");

        private final String description;
        Phase(String desc) { this.description = desc; }
        public String description() { return description; }
    }

    private State state = State.DEFAULT;
    private State prePlanState = State.DEFAULT;
    private Phase currentPhase = Phase.UNDERSTAND;
    private String currentPlan;
    private String planSlug;
    private final Path plansDir;
    private int planReentryCount;
    private boolean planWasEdited;

    // Parallel exploration tracking
    private final Map<String, ExploreAgentState> exploreAgents = new ConcurrentHashMap<>();
    private int exploreAgentCount = 3;
    private int planAgentCount = 1;

    public PlanMode() { this(Paths.get(System.getProperty("user.home"), ".mocha", "plans")); }
    public PlanMode(Path plansDir) { this.plansDir = plansDir; }

    // ============ State transitions ============

    /** Enter plan mode — switches to read-only exploration (Phase 1: UNDERSTAND). */
    public boolean enterPlan() {
        if (state == State.PLAN) return false;
        prePlanState = state;
        state = State.PLAN;
        currentPhase = Phase.UNDERSTAND;
        log.info("Entered plan mode — Phase 1: UNDERSTAND (read-only)");
        return true;
    }

    /** Advance to next phase. Returns false if already at EXIT. */
    public boolean advancePhase() {
        Phase[] phases = Phase.values();
        int idx = currentPhase.ordinal();
        if (idx >= phases.length - 1) return false;
        currentPhase = phases[idx + 1];
        log.info("Plan mode: Phase {} → {}", idx + 1, currentPhase);
        return true;
    }

    /** Jump to a specific phase. */
    public void setPhase(Phase phase) {
        this.currentPhase = phase;
        log.info("Plan mode: jumped to Phase {}", phase);
    }

    /** Exit plan mode with an approved plan. Saves plan to disk. */
    public boolean exitPlan(String planContent) {
        if (state != State.PLAN) return false;
        this.currentPlan = planContent;
        this.state = State.APPROVED;
        this.currentPhase = Phase.EXIT;
        savePlan(planContent);
        log.info("Plan approved — entering implementation mode");
        return true;
    }

    /** Revert to pre-plan state (user cancelled or rejected). */
    public void cancelPlan() {
        state = prePlanState;
        currentPlan = null;
        currentPhase = Phase.UNDERSTAND;
        exploreAgents.clear();
        log.info("Plan cancelled, reverted to {}", prePlanState);
    }

    /**
     * Re-enter plan mode after exit. Claude-code pattern: on re-entry,
     * the model must re-evaluate the existing plan.
     */
    public boolean reenterPlan() {
        if (state != State.APPROVED) return false;
        state = State.PLAN;
        currentPhase = Phase.REVIEW; // Skip to review phase on re-entry
        planReentryCount++;
        log.info("Plan mode re-entry #{} — re-evaluating existing plan", planReentryCount);
        return true;
    }

    // ============ Plan persistence ============

    private void savePlan(String content) {
        try {
            Files.createDirectories(plansDir);
            if (planSlug == null) planSlug = generateSlug();
            Path planFile = plansDir.resolve(planSlug + ".md");
            Files.writeString(planFile, content);
            log.info("Plan saved: {} ({} chars)", planFile, content.length());
        } catch (IOException e) { log.warn("Cannot save plan: {}", e.getMessage()); }
    }

    /** Append to the plan file (incremental update). */
    public void appendToPlan(String additionalContent) {
        try {
            if (planSlug == null) planSlug = generateSlug();
            Path planFile = plansDir.resolve(planSlug + ".md");
            Files.createDirectories(plansDir);
            String existing = Files.exists(planFile) ? Files.readString(planFile) : "";
            Files.writeString(planFile, existing + "\n" + additionalContent);
            log.debug("Plan appended: {} chars", additionalContent.length());
        } catch (IOException e) { log.warn("Cannot append to plan: {}", e.getMessage()); }
    }

    public String loadPlan(String slug) {
        try {
            Path file = plansDir.resolve(slug + ".md");
            return Files.exists(file) ? Files.readString(file) : null;
        } catch (IOException e) { return null; }
    }

    public String loadCurrentPlan() {
        return planSlug != null ? loadPlan(planSlug) : null;
    }

    public List<String> listPlans() {
        try {
            if (!Files.exists(plansDir)) return List.of();
            try (var stream = Files.newDirectoryStream(plansDir, "*.md")) {
                List<String> slugs = new ArrayList<>();
                for (Path p : stream) slugs.add(p.getFileName().toString().replace(".md", ""));
                return slugs;
            }
        } catch (IOException e) { return List.of(); }
    }

    /**
     * Recover plan on session resume. Claude-code pattern: try disk first,
     * then transcript, then user message.
     */
    public String recoverPlan() {
        // 1. Try disk
        if (planSlug != null) {
            String fromDisk = loadPlan(planSlug);
            if (fromDisk != null && !fromDisk.isEmpty()) return fromDisk;
        }
        // 2. Try current in-memory
        if (currentPlan != null && !currentPlan.isEmpty()) return currentPlan;
        return null;
    }

    // ============ Parallel exploration agents ============

    /** Register a launched Explore agent for tracking. */
    public void registerExploreAgent(String agentId, String focus) {
        exploreAgents.put(agentId, new ExploreAgentState(agentId, focus, "running"));
        log.info("Explore agent {} launched: {}", agentId, focus);
    }

    /** Mark an Explore agent as completed. */
    public void completeExploreAgent(String agentId, String findings) {
        ExploreAgentState agent = exploreAgents.get(agentId);
        if (agent != null) {
            exploreAgents.put(agentId, new ExploreAgentState(agentId, agent.focus(), "completed"));
            log.info("Explore agent {} completed: {} chars of findings", agentId,
                    findings != null ? findings.length() : 0);
        }
    }

    /** Get findings from all completed Explore agents. */
    public Map<String, String> collectExploreFindings() {
        Map<String, String> findings = new LinkedHashMap<>();
        for (var entry : exploreAgents.entrySet()) {
            if ("completed".equals(entry.getValue().status())) {
                findings.put(entry.getKey(), entry.getValue().focus());
            }
        }
        return findings;
    }

    /** Check if all Explore agents have completed. */
    public boolean allExploreAgentsDone() {
        return exploreAgents.isEmpty() || exploreAgents.values().stream()
                .allMatch(a -> "completed".equals(a.status()));
    }

    // ============ Configurable limits ============

    public void setExploreAgentCount(int count) { this.exploreAgentCount = Math.max(1, count); }
    public void setPlanAgentCount(int count) { this.planAgentCount = Math.max(1, count); }
    public int exploreAgentCount() { return exploreAgentCount; }
    public int planAgentCount() { return planAgentCount; }

    // ============ Getters ============

    public State state() { return state; }
    public Phase currentPhase() { return currentPhase; }
    public String currentPlan() { return currentPlan; }
    public String planSlug() { return planSlug; }
    public boolean isReadOnly() { return state == State.PLAN; }
    public boolean wasPlanEdited() { return planWasEdited; }
    public void markPlanEdited() { this.planWasEdited = true; }
    public int planReentryCount() { return planReentryCount; }
    public boolean isInPlanMode() { return state == State.PLAN; }

    // ============ Instructions per phase ============

    /** Get the system prompt instructions for the current phase. */
    public String getPhaseInstructions() {
        return switch (currentPhase) {
            case UNDERSTAND -> """
                    [Plan Mode — Phase 1: UNDERSTAND]
                    You are in read-only exploration mode. Your goal is to understand the problem.
                    1. Read relevant code and documentation.
                    2. Launch up to %d Explore agents to search the codebase in parallel.
                    3. Ask clarifying questions if needed.
                    Do NOT write any code or make any changes yet.
                    """.formatted(exploreAgentCount);

            case DESIGN -> """
                    [Plan Mode — Phase 2: DESIGN]
                    Design the implementation approach. Consider:
                    1. Multiple alternatives with tradeoffs.
                    2. Impact on existing code — what changes, what stays.
                    3. Edge cases and failure modes.
                    Launch up to %d Plan agents for different perspectives.
                    """.formatted(planAgentCount);

            case REVIEW -> """
                    [Plan Mode — Phase 3: REVIEW]
                    Review the design:
                    1. Read the critical files identified by your exploration.
                    2. Ensure the plan aligns with the user's intent.
                    3. Resolve any ambiguities before finalizing.
                    """;

            case FINAL_PLAN -> """
                    [Plan Mode — Phase 4: FINAL PLAN]
                    Write the complete plan to the plan file. Include:
                    - Context and problem statement
                    - Recommended approach with rationale
                    - Files to modify and specific changes
                    - Verification steps
                    Keep it concise — the plan is a reference, not a novel.
                    """;

            case EXIT -> """
                    [Plan Mode — Phase 5: EXIT]
                    Call ExitPlanMode to present your plan for approval.
                    The user will review and approve or request changes.
                    """;
        };
    }

    private static String generateSlug() {
        String[] adjectives = {"brave", "calm", "eager", "keen", "bold", "wise", "swift", "sharp"};
        String[] nouns = {"lion", "hawk", "wolf", "fox", "bear", "deer", "owl", "dove"};
        Random rng = new Random();
        return adjectives[rng.nextInt(adjectives.length)]
                + "-" + nouns[rng.nextInt(nouns.length)]
                + "-" + (rng.nextInt(90) + 10);
    }

    // ============ Types ============

    private record ExploreAgentState(String agentId, String focus, String status) {}
}
