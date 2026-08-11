package com.flamingo.tiktaktoe.engine.controller;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.engine.service.GameEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the Game Engine. Exposes exactly the endpoints from
 * the assignment (task.md): move and state retrieval. Thin — all rules live in
 * {@link GameEngineService}, and the mapping of its exceptions to HTTP statuses
 * lives in {@link com.flamingo.tiktaktoe.engine.exception.GameExceptionHandler}.
 *
 * <p>Responses are {@link GameState}, the shared API model from {@code common} —
 * never the JPA entity, which does not leave the service layer.
 */
@Tag(name = "Games", description = "Tic-Tac-Toe game state and move submission")
@RestController
@RequestMapping("/games")
public class GameController {

    private final GameEngineService service;

    public GameController(GameEngineService service) {
        this.service = service;
    }

    /**
     * Applies a move. Creates the game first if the id is unknown — Engine's
     * move endpoint is an upsert, so Session needs no separate "create" call.
     *
     * @param gameId the game to move in
     * @param move   the move to apply
     * @return the game's state after the move
     */
    @Operation(summary = "Submit a move",
            description = "Validates and applies the move, then returns the resulting state. "
                    + "Creates the game when the id is not yet known (upsert).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Move applied; the resulting state is returned"),
            @ApiResponse(responseCode = "400", description = "Malformed body, or the target cell is not playable"),
            @ApiResponse(responseCode = "409", description = "Game already finished, not this player's turn, "
                    + "or a concurrent move won the race")
    })
    @PostMapping("/{gameId}/move")
    public GameState makeMove(@PathVariable String gameId, @Valid @RequestBody MoveRequest move) {
        return service.makeMove(gameId, move);
    }

    /**
     * Fetches a game's current state.
     *
     * @param gameId the game id
     * @return the game's current state
     */
    @Operation(summary = "Get a game's current state",
            description = "Returns the board, status, whose turn it is, and the winner if the game is decided.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Game state returned"),
            @ApiResponse(responseCode = "404", description = "No game with the given id exists")
    })
    @GetMapping("/{gameId}")
    public GameState getGame(@PathVariable String gameId) {
        return service.getGame(gameId);
    }
}
