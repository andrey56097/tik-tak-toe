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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class GameRepositoryTest {

    @Autowired
    private GameRepository repository;

    @Autowired
    private EntityManager entityManager;

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

    @Test
    void secondWriteFromAStaleCopyIsRejected() {
        GameEntity saved = repository.save(
                new GameEntity(null, "board", GameStatus.IN_PROGRESS, CellState.X));
        repository.flush();
        entityManager.clear();

        // Two callers that both read the game before either wrote it back. Without
        // @Version the later write would silently overwrite the earlier one and both
        // moves would "succeed"; with it, the loser is rejected and the caller can be
        // told 409.
        GameEntity first = repository.findById(saved.getId()).orElseThrow();
        entityManager.detach(first);
        GameEntity second = repository.findById(saved.getId()).orElseThrow();
        entityManager.detach(second);

        first.setNextTurn(CellState.O);
        repository.saveAndFlush(first);
        entityManager.clear();

        second.setNextTurn(CellState.X);
        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void versionAdvancesOnEveryUpdate() {
        GameEntity saved = repository.saveAndFlush(
                new GameEntity(null, "board", GameStatus.IN_PROGRESS, CellState.X));
        long initialVersion = saved.getVersion();

        saved.setNextTurn(CellState.O);
        long afterUpdate = repository.saveAndFlush(saved).getVersion();

        // The version is what the stale-write check compares; if it never moved, the
        // check above could never fire.
        assertThat(afterUpdate).isGreaterThan(initialVersion);
    }
}
