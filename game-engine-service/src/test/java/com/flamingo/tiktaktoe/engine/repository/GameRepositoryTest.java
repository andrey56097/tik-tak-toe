package com.flamingo.tiktaktoe.engine.repository;

import com.flamingo.tiktaktoe.engine.controller.*;
import com.flamingo.tiktaktoe.engine.service.*;
import com.flamingo.tiktaktoe.engine.domain.*;
import com.flamingo.tiktaktoe.engine.repository.*;
import com.flamingo.tiktaktoe.engine.validation.*;
import com.flamingo.tiktaktoe.engine.exception.*;
import com.flamingo.tiktaktoe.engine.mapper.*;

import com.flamingo.tiktaktoe.common.CellState;
import com.flamingo.tiktaktoe.common.GameStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class GameRepositoryTest {

    @Autowired
    private GameRepository repository;

    @Test
    void savesAndReadsGame() {
        GameEntity entity = new GameEntity(
                null,
                "[[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"],[\"EMPTY\",\"EMPTY\",\"EMPTY\"]]",
                GameStatus.IN_PROGRESS,
                CellState.X
        );

        GameEntity saved = repository.save(entity);
        GameEntity found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getBoard()).contains("EMPTY");
        assertThat(found.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(found.getNextTurn()).isEqualTo(CellState.X);
    }

    @Test
    void generatesIdOnPersist() {
        GameEntity entity = new GameEntity(null, "board", GameStatus.IN_PROGRESS, CellState.X);
        GameEntity saved = repository.save(entity);
        assertThat(saved.getId()).isNotBlank();
    }
}
