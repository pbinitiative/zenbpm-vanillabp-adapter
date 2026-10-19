/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.message;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The postcards of the message IT.
 */
public interface PostcardRepository extends JpaRepository<Postcard, String> {
}
