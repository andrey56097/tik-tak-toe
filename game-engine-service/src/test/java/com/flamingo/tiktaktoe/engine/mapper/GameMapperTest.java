package com.flamingo.tiktaktoe.engine.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameState;
import com.flamingo.tiktaktoe.common.GameStatus;
import com.flamingo.tiktaktoe.engine.domain.GameEntity;
import com.flamingo.tiktaktoe.engine.exception.BoardMappingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameMapperTest {

    private final GameMapper mapper = new GameMapper(new ObjectMapper());

    @Test
    void toStateMapsAllFields() {
        String boardJson = "[[\"X\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"O\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]";
        GameEntity entity = new GameEntity("g1", boardJson, GameStatus.IN_PROGRESS, CellState.X);
        entity.setWinner(null);

        GameState state = mapper.toState(entity);

        assertThat(state.id()).isEqualTo("g1");
        assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(state.nextTurn()).isEqualTo(CellState.X);
        assertThat(state.winner()).isNull();
        assertThat(state.board().get(0).get(0)).isEqualTo(CellState.X);
        assertThat(state.board().get(1).get(1)).isEqualTo(CellState.O);
        assertThat(state.board().get(2).get(2)).isEqualTo(CellState.EMPTY);
    }

    @Test
    void toStateMapsWinner() {
        String boardJson = "[[\"X\",\"O\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]";
        GameEntity entity = new GameEntity("g1", boardJson, GameStatus.WIN, CellState.O);
        entity.setWinner(CellState.X);

        GameState state = mapper.toState(entity);

        assertThat(state.status()).isEqualTo(GameStatus.WIN);
        assertThat(state.winner()).isEqualTo(CellState.X);
    }

    @Test
    void writeBoardThenParseBoardRoundTrips() {
        List<List<CellState>> board = List.of(
                List.of(CellState.X, CellState.EMPTY, CellState.O),
                List.of(CellState.EMPTY, CellState.O, CellState.X),
                List.of(CellState.O, CellState.X, CellState.EMPTY)
        );
        GameEntity entity = new GameEntity("g1", null, GameStatus.IN_PROGRESS, CellState.X);

        mapper.writeBoard(entity, board);
        List<List<CellState>> parsed = mapper.parseBoard(entity.getBoard());

        assertThat(parsed).isEqualTo(board);
    }

    @Test
    void parseBoardHandlesEmptyCells() {
        List<List<CellState>> board = mapper.parseBoard(
                "[[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]"
        );
        assertThat(board).hasSize(3);
        assertThat(board.get(0)).containsExactly(CellState.EMPTY, CellState.EMPTY, CellState.EMPTY);
    }

    @Test
    void parseBoardReturnsExplicitlyMutableLists() {
        List<List<CellState>> board = mapper.parseBoard(
                "[[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]"
        );

        board.get(0).set(0, CellState.X);
        board.add(List.of(CellState.O, CellState.O, CellState.O));

        assertThat(board.get(0).get(0)).isEqualTo(CellState.X);
        assertThat(board).hasSize(4);
    }

    @Test
    void parseBoardThrowsBoardMappingExceptionOnInvalidJson() {
        assertThatThrownBy(() -> mapper.parseBoard("not valid json"))
                .isInstanceOf(BoardMappingException.class)
                .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
