/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.message;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The letters of the message IT.
 */
public interface LetterRepository extends JpaRepository<Letter, String> {
}
