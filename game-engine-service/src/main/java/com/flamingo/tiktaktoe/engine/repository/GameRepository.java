package com.flamingo.tiktaktoe.engine.repository;

import com.flamingo.tiktaktoe.engine.domain.GameEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link GameEntity}. Business logic depends only on this
 * interface, never on JPA/H2 specifics (swappable per project rules).
 */
public interface GameRepository extends JpaRepository<GameEntity, String> {
}
