package com.flamingo.tiktaktoe.engine.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.engine.domain.GameEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts between the JPA {@link GameEntity} and the API {@link GameState}.
 * The board is stored in the entity as a JSON string.
 */
@Component
public class GameMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeReference<List<List<CellState>>> BOARD_TYPE = new TypeReference<>() {
    };

    public GameState toState(GameEntity entity) {
        List<List<CellState>> board = parseBoard(entity.getBoard());
        return new GameState(
                entity.getId(),
                board,
                entity.getStatus(),
                entity.getNextTurn(),
                entity.getWinner()
        );
    }

    public void writeBoard(GameEntity entity, List<List<CellState>> board) {
        entity.setBoard(writeBoardJson(board));
    }

    public List<List<CellState>> parseBoard(String json) {
        try {
            return objectMapper.readValue(json, BOARD_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse board", e);
        }
    }

    private String writeBoardJson(List<List<CellState>> board) {
        try {
            return objectMapper.writeValueAsString(board);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize board", e);
        }
    }
}
