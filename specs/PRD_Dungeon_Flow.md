# **Dungeon Flow**

## **AI Solutions Product Requirements Document**

**Version:** 0.1

**Prepared by:** Fady (FDE) — drafted with Claude

**Organization:** DataRobot — Field Delivery Engineering

**Date Modified:** 2026-08-10

This document defines the *what* and *why* of the product — from strategic alignment and market context through to feature priorities and critical user journeys. Technical requirements, architecture, and implementation details are owned by the companion SRS document (`docs/SRS_Dungeon_Flow.md`).

---

## **Completeness Scorecard**

| Section | Status | Confidence | Notes |
| :---- | :---- | :---- | :---- |
| 1. Summary | Complete | High | |
| 2. Jobs to Be Done | Complete | High | |
| 3. Problem Statement | Partial | Medium | Problems confirmed by product owner only; no external validation needed for an internal enablement demo |
| 4. North Star Solution | Complete | High | |
| 5. Critical User Journeys | Complete | High | |
| 6. Feature Priorities | Complete | High | |
| 7. Critical Decisions / Open Questions | Complete | Medium | Two open questions, non-blocking for Crawl |

---

## **Part I: Strategic Alignment (The "What" and "Why")**

### **1. Summary**

Dungeon Flow is a containerized text-adventure game in which a Serverless Workflow definition running on Quarkus (SonataFlow) **is** the dungeon map: rooms are workflow states, doors are transitions, and player moves are CloudEvents. It exists because workflow-engine concepts (event states, switches, joins, retries, timeouts, compensation) are abstract and hard to teach, and conventional order-processing demos fail to land with mixed technical audiences. The target users are FDE/engineering team members learning workflow orchestration and presenters demonstrating it live. The expected outcome is a memorable, self-contained enablement asset: a single container that any teammate can run, play via curl or a minimal UI, and project as a live diagram during demos — turning "what does a workflow engine do?" into something the audience watches happen.

### **2. Jobs to Be Done**

#### **Core Job: Learn workflow-engine concepts by playing**

*Performed by engineers and mixed-technical learners who need to internalize orchestration patterns quickly.*

#### **Key Stages & Associated Jobs**

##### **Getting started**

- **When:** I'm handed a new workflow technology to learn
- **I want to:** run one container and immediately interact with a live workflow instance
- **So I can:** build intuition from doing, not from reading spec documents

##### **Exploring core patterns**

- **When:** I'm playing through the dungeon
- **I want to:** encounter each workflow primitive (event wait, branch, join, retry, timeout) as a game mechanic
- **So I can:** map an abstract concept to a concrete, memorable experience

##### **Inspecting the mechanics**

- **When:** something surprising happens in the game (a respawn, a timeout)
- **I want to:** open the workflow definition and diagram and see exactly which construct caused it
- **So I can:** connect game behavior back to the underlying definition

#### **Core Job: Demonstrate workflow orchestration live**

*Performed by a presenter (FDE, SA, or team lead) in enablement sessions or customer-facing demos.*

#### **Key Stages & Associated Jobs**

##### **Preparing the demo**

- **When:** I have an enablement session or demo slot coming up
- **I want to:** spin the game up from a single container image with zero bespoke setup
- **So I can:** demo reliably on any laptop or shared environment

##### **Presenting**

- **When:** I'm live in front of an audience
- **I want to:** project the workflow diagram and have the audience watch the token move as a volunteer plays
- **So I can:** make the engine's behavior visible instead of describing it

##### **Handling questions**

- **When:** someone asks "what happens if…"
- **I want to:** trigger that scenario live (idle past the timeout, fail the lock repeatedly)
- **So I can:** answer with the running system instead of slides

#### **Core Job: Run a multiplayer race**

*Performed by a facilitator running a team event or workshop icebreaker.*

#### **Key Stages & Associated Jobs**

##### **Setting up the race**

- **When:** I'm facilitating a workshop with several participants
- **I want to:** spawn one workflow instance per player and hand each player their handle
- **So I can:** run a competitive race on a single shared container

##### **Racing**

- **When:** the race is underway
- **I want to:** see which instances are still alive and which have finished
- **So I can:** declare a winner and keep energy high

---

#### **Operate & Monitor**

- **When:** the game has been running through a session
- **I want to:** list, inspect, and clean up workflow instances
- **So I can:** reset the environment between groups without restarting the container

### **3. Problem Statement**

Workflow orchestration concepts are consistently among the harder topics to teach in engineering enablement: the vocabulary (event states, correlation, joins, compensation) is abstract, and typical demos (order fulfillment, loan approval) are forgettable. Engineers experience this every time a workflow technology enters the stack — the gap is between "read the spec" (hours, low retention) and "felt it work" (minutes, high retention). An interactive game makes each primitive observable and memorable, and doubles as a reusable demo asset. This aligns with the FDE team's enablement-platform goals of hands-on, self-serve learning assets.

| Problem | Measurable Impact | Confirmed By |
| :---- | :---- | :---- |
| Workflow-engine primitives are abstract and poorly retained from documentation alone | Onboarding to a workflow engine takes days of reading before productive use; concepts like joins/correlation commonly need re-explaining | Fady (FDE manager, product owner) |
| Standard workflow demos don't engage mixed audiences | Demo sections on orchestration produce low audience interaction vs. hands-on segments | Fady (FDE manager, product owner) |

### **4. North Star Solution**

**North Star Vision:**

A single container anyone on the team can pull and run that hosts a playable dungeon whose map is literally the workflow definition. Players interact through HTTP (curl or a minimal web page); presenters project the live workflow diagram so audiences watch the token traverse rooms in real time. The same asset serves onboarding (self-paced play), demos (projector mode), and workshops (multiplayer race) — fully closing the gap between abstract workflow concepts and lived experience.

#### **Crawl–Walk–Run Implementation Strategy**

##### **Crawl:** Playable 5-room dungeon via curl, runnable in Quarkus dev mode with the Dev UI diagram as projector view

*Observable outcomes:*

- A player can start an instance and reach the Treasure Room end state using only curl
- All five primitives are triggerable: event wait, data-based switch, two-event join, retry-with-respawn, idle timeout
- The workflow diagram is viewable in the Dev UI while an instance progresses

##### **Walk:** Container image + minimal HTML front end + multiplayer race

*Observable outcomes:*

- The game runs from a built container image with no local toolchain
- A player can complete the dungeon using only a browser page (narrative + choice buttons)
- Two or more players can race concurrently on one container, each on an isolated instance

##### **Run:** Correlation-based player identity + LLM-refereed boss fight

*Observable outcomes:*

- Players are identified by a correlation attribute (e.g., `playerid`) instead of raw workflow instance IDs
- An optional boss-fight room calls an LLM to referee a riddle, with a deterministic fallback if the LLM is unavailable

---

## **Part II: Product Definition (The "What")**

### **5. Critical User Journeys (CUJs)**

#### **[PRD-CUJ-01] Player completes the dungeon** *(Core Job: Learn by playing)*

**Trigger:** Player POSTs to the workflow start endpoint.

**Success state:** The player's instance reaches the Treasure Room end state and the player has seen the victory narrative.

* Player starts a new game; system creates a workflow instance and returns the Entrance narrative plus the instance handle
* Player sends a choice event ("left" or "right"); system routes the instance through the switch to the corresponding room
* (Left path) Player pulls Lever A and Lever B in any order; system holds the gate until **both** events arrive, then advances
* System runs the Trap Corridor: the lock-pick action fails randomly and is retried up to 3 times with delay
* On retry exhaustion, system respawns the player at the fork with a narrative explaining the trap; player chooses again
* If the player idles past the torch timeout while a choice is pending, system fires the timeout and respawns the player at the Entrance
* On success, system delivers the Treasure Room narrative and completes the instance
* Player queries instance state at any time and receives the current room's narrative (or a completed/not-found signal after victory)

#### **[PRD-CUJ-02] Presenter runs a live demo** *(Core Job: Demonstrate live)*

**Trigger:** Presenter starts the app (dev mode or container) before a session.

**Success state:** Audience has watched a token traverse the diagram from Entrance to Treasure Room, including at least one retry/timeout event.

* Presenter starts the application and opens the workflow diagram view on the projector
* A volunteer plays through [PRD-CUJ-01] while the presenter narrates each primitive as the token moves
* Presenter deliberately triggers a failure path (idle past the timeout, or let the lock jam) to show error handling live
* Presenter opens the workflow definition file to connect the observed behavior to the construct that caused it

#### **[PRD-CUJ-03] Facilitator runs a multiplayer race** *(Core Job: Multiplayer race)*

**Trigger:** Facilitator has N participants and one running container.

**Success state:** A winner is declared when the first instance reaches the end state; environment is reset for the next group.

* Facilitator starts N instances and distributes one handle per player
* Players progress independently; system keeps instances fully isolated
* Facilitator lists active instances to see who is still alive and who has finished
* First instance to complete wins; facilitator cleans up remaining instances to reset

### **6. Feature Priorities**

| Priority | Description |
| :---- | :---- |
| | **Job 1 — Learn by playing ([PRD-CUJ-01])** |
| P0 | [PRD-FEAT-01] Workflow-as-map: 5-room dungeon defined entirely in one Serverless Workflow file (Entrance, Fork, Lever Room, Trap Corridor, Treasure Room) |
| P0 | [PRD-FEAT-02] Instance-per-player game start via HTTP, returning narrative + instance handle |
| P0 | [PRD-FEAT-03] Choice routing: event wait + data-based switch (left/right) |
| P0 | [PRD-FEAT-04] Two-lever join puzzle: gate opens only after both lever events arrive |
| P0 | [PRD-FEAT-05] Trap Corridor: random lock failure with bounded retries and respawn-to-fork on exhaustion |
| P1 | [PRD-FEAT-06] Torch timeout: idle timeout on the pending choice that respawns the player at the Entrance |
| P1 | [PRD-FEAT-07] State inspection: query current narrative/room for any instance |
| | **Job 2 — Demonstrate live ([PRD-CUJ-02])** |
| P0 | [PRD-FEAT-08] Container packaging: single image build; game fully playable from the container |
| P1 | [PRD-FEAT-09] Projector view: live workflow diagram (Dev UI in Crawl) showing token position |
| P2 | [PRD-FEAT-10] Guided demo script: documented sequence for triggering each primitive on cue (README demo section) |
| | **Job 3 — Multiplayer race ([PRD-CUJ-03])** |
| P1 | [PRD-FEAT-11] Concurrent isolated instances: N players on one container without cross-talk |
| P2 | [PRD-FEAT-12] Race operations: list active/completed instances; bulk cleanup/reset |
| P2 | [PRD-FEAT-13] Minimal HTML front end: narrative display + choice/lever buttons (Walk phase) |
| P2 | [PRD-FEAT-14] LLM boss fight: optional riddle room refereed by an LLM with deterministic fallback (Run phase) |

### **7. Critical Decisions / Open Questions**

| # | Question | Section Affected | Priority | Owner | Target Date | Status |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- |
| 1 | Event-to-instance routing for Crawl: raw instance ID reference vs. correlation attribute (`playerid`)? Instance ID is simplest for Crawl; correlation is the Run-phase target | 5, 6 | Medium | Fady | Before Walk phase | Open |
| 2 | Which SonataFlow/Quarkus platform version to pin? Event-routing details shift between releases | 6 | Medium | Fady | At project scaffold | Open |

---

## **Appendix A: Glossary**

| Term | Definition |
| :---- | :---- |
| **PRD** | Product Requirements Document — this document. Defines the what and why of the product. |
| **SRS** | Software Requirements Specification — the companion document. Defines the how of the solution. |
| **CUJ** | Critical User Journey — an end-to-end sequence of steps a user must complete successfully for the product to deliver value. |
| **JTBD** | Jobs to Be Done — a framework for understanding the outcomes users seek, independent of specific features. |
| **P0 / P1 / P2** | Priority 0 (launch-blocking) / Priority 1 (important, post-launch OK) / Priority 2 (nice to have). |
| **Serverless Workflow (SWF)** | CNCF specification for declaratively defining event-driven workflows; the dungeon map format. |
| **SonataFlow** | Quarkus-based implementation of the Serverless Workflow specification (Apache KIE). |
| **CloudEvent** | CNCF standard envelope for event data; the format of every player move. |
| **Workflow instance** | One running execution of the workflow definition; equals one player's game session. |
| **Join** | A synchronization point that waits for multiple events before proceeding (the two-lever gate). |
| **LLM** | Large Language Model — used only in the optional Run-phase boss fight. |

## **Appendix B: Open Questions & Discovery Gaps**

| # | Question | Section Affected | Priority | Status | SRS Cross-Ref? |
| :---- | :---- | :---- | :---- | :---- | :---- |
| 1 | Should the Walk-phase HTML front end poll instance state or subscribe to emitted events? | 4, 6 | Low | Open | Yes (SRS Appendix C #1) |
| 2 | Is there appetite to reuse this asset in customer-facing enablement (would raise polish bar on UI/copy)? | 1, 4 | Low | Open | No |
