# 🦩 Backend Home Assignment: Distributed Tic Tac Toe Microservices

## 📌 Overview
The goal of this assignment is to build a distributed Tic Tac Toe application where the game is played automatically by microservices. 

The system consists of three main components:
1. **Game Engine Service**: Manages core game logic, board state, move validation, and game outcomes.
2. **Game Session Service**: Oversees sessions and automates moves for both players by communicating with the Game Engine Service.
3. **User Interface (UI)**: Visually displays the 3x3 board and real-time progress as microservices play against each other.

---

## 🛠️ Components & Requirements

### 1. Game Engine Service
**Objective:** Implement core Tic Tac Toe logic and state management.

- [ ] **Responsibilities:**
  - **Board Management:** Maintain current board state.
  - **Move Validation:** Ensure moves are legal (e.g., target cell is empty).
  - **Game Outcome:** Detect win conditions or draws after every move.
- [ ] **API Endpoints:**
  - `POST /games/{gameId}/move`
    - *Input:* Player symbol, board position.
    - *Functionality:* Validate move, update state, return status (`IN_PROGRESS`, `WIN`, `DRAW`).
  - `GET /games/{gameId}`
    - *Functionality:* Retrieve current board state and status.
- [ ] **Technical Requirements:**
  - In-memory state storage (In-memory data structures or H2 database).
  - Robust error handling for invalid moves and end-of-game scenarios.

---

### 2. Game Session Service
**Objective:** Manage sessions and automate gameplay.

- [ ] **Responsibilities:**
  - **Session Management:** Create and maintain game sessions.
  - **Automated Move Generation:** Simulate player moves (random or rule-based) and send to Game Engine Service.
  - **Move Coordination:** Process Game Engine responses and update session logs.
- [ ] **API Endpoints:**
  - `POST /sessions`
    - *Functionality:* Generate unique `sessionId` (can double as `gameId`), optionally initialize game state in Game Engine.
  - `POST /sessions/{sessionId}/simulate`
    - *Functionality:* Trigger automated move simulation (alternating turns) until game concludes.
  - `GET /sessions/{sessionId}`
    - *Functionality:* Fetch session details, move history, and current game state.
- [ ] **Technical Requirements:**
  - In-memory storage (H2, HashMap, etc.) for session and move history.
  - Reliable REST communication with Game Engine Service and error handling.

---

### 3. User Interface (UI)
**Objective:** Provide a visual interface showing live progress of the automated game.

- [ ] **UI Responsibilities:**
  - **Game Initialization:** "Start Simulation" button to trigger `POST /sessions/{sessionId}/simulate`.
  - **Game Display:** Render a 3x3 board updated in real time as microservices play.
  - **Status & Feedback:** Show current status (`IN_PROGRESS`, `WIN`, `DRAW`) and move history log.
  - **Error Handling:** Display backend or network error messages.
  - **Real-Time Feedback (Optional):** Integration with WebSockets or Server-Sent Events (SSE).

---

## 🧪 Testing & Validation

- [ ] **Inter-Service Communication:** Verify REST API interactions between Session and Engine services.
- [ ] **State Management:** Ensure consistent state updates across services.
- [ ] **Error Handling:** Validate response behavior for invalid moves and connection issues.
- [ ] **Integration Tests:** Cover complete automated flow: `Session Creation` ➔ `Move Simulation` ➔ `Game Outcome`.

---

## ⭐ Optional Enhancements

- [ ] **Concurrency Handling:** Safely process concurrent move requests.
- [ ] **Service Discovery / API Gateway:** Integrate Spring Cloud Gateway or Netflix Eureka.
- [ ] **Data Persistence:** Implement strategies for persistent storage and state recovery.
- [ ] **Real-Time Updates:** Add WebSockets or SSE for fluid UI board updates.

---

## ✅ Submission Checklist

- [ ] **Code Quality:** Clean, well-structured, commented code following Spring Boot best practices.
- [ ] **Documentation:** `README.md` with build, run, and test instructions.
- [ ] **Testing:** Comprehensive integration tests.
- [ ] **Discussion (Optional):** Summary of potential architecture improvements or alternative designs.