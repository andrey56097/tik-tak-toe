package com.flamingo.tiktaktoe.engine.controller;

import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.MoveRequest;
import com.flamingo.tiktaktoe.engine.service.GameEngineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the Game Engine. Exposes exactly the endpoints from
 * the assignment (task.md): move and state retrieval.
 */
@RestController
@RequestMapping("/games")
public class GameController {

    private final GameEngineService service;

    public GameController(GameEngineService service) {
        this.service = service;
    }

    @PostMapping("/{gameId}/move")
    public GameState makeMove(@PathVariable String gameId, @RequestBody MoveRequest move) {
        return service.makeMove(gameId, move);
    }

    @GetMapping("/{gameId}")
    public GameState getGame(@PathVariable String gameId) {
        return service.getGame(gameId);
    }
}
