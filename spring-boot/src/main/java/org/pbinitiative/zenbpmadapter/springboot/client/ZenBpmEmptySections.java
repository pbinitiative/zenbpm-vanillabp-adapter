/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/adapter-platform-integration, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.springboot.client;

import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * Makes a section of the <code>vanillabp</code> tree which is written as <code>{}</code> keep the
 * keys of this adapter which other property sources set below it.
 * <p>
 * <code>vanillabp.adapters.zenbpm: {}</code> is the shortest way to configure an adapter id. Spring
 * Boot reads the empty map of a YAML file as a property with an empty value,
 * <code>vanillabp.adapters.zenbpm=</code>. When it binds an object, it first asks every property
 * source for a value at the object's own name, and only then for the keys below it. So the empty
 * value wins, an empty section is bound, and every key of this adapter from the command line or an
 * environment variable is lost: <code>rest-address</code> first of all, which leaves the adapter id
 * unconfigured.
 * <p>
 * A section never has a value of its own. So where keys exist below a section, the section is bound
 * with {@link Bindable.BindRestriction#NO_DIRECT_PROPERTY}, which tells Spring Boot to skip the
 * value at the section's name and to read the keys below it. Where nothing exists below the name,
 * the binding stays as it was.
 * <p>
 * The platform applies the same rule to the types of VanillaBP, which it knows by their package
 * below <code>io.vanillabp</code>. The types of {@link VanillaBpZenBpmProperties} live in this
 * adapter's packages, so the platform's rule passes them by and this class applies it to them, see
 * decision 71 in the repository's DECISIONS.md. The rule relies on one promise: a type of this
 * adapter below <code>vanillabp</code> is always a section. A single value is a JDK type or an
 * enum, and enums are left out; a class of this adapter which is converted from a single text would
 * lose its value.
 */
public final class ZenBpmEmptySections {

    private static final String TYPES_OF_THIS_ADAPTER = "org.pbinitiative.zenbpmadapter.";

    private ZenBpmEmptySections() {
    }

    /**
     * The advisor which applies the rule to the overlay of this adapter. Its sections are values of
     * maps, so only a bind handler reaches them. Spring Boot uses every such bean while it binds a
     * {@code @ConfigurationProperties} class.
     *
     * @return The advisor
     */
    public static ConfigurationPropertiesBindHandlerAdvisor advisor() {
        return SectionsKeepTheKeysBelowThem::new;
    }

    private static boolean isSectionWithKeysBelowIt(
        final ConfigurationPropertyName name,
        final Bindable<?> target,
        final BindContext context
    ) {
        if (name.isEmpty() || !MigrationAdapterProperties.PREFIX.equals(name.getElement(0,
                ConfigurationPropertyName.Form.UNIFORM))) {
            return false;
        }
        final var type = target
                .getType()
                .resolve();
        if ((type == null) || type.isEnum() || !type
                .getName()
                .startsWith(TYPES_OF_THIS_ADAPTER)) {
            return false;
        }
        for (final var source : context.getSources()) {
            if (source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
                return true;
            }
        }
        return false;
    }

    private static final class SectionsKeepTheKeysBelowThem extends AbstractBindHandler {

        private SectionsKeepTheKeysBelowThem(final BindHandler parent) {
            super(parent);
        }

        @Override
        public <T> Bindable<T> onStart(
            final ConfigurationPropertyName name,
            final Bindable<T> target,
            final BindContext context
        ) {
            final var replacement = super.onStart(name, target, context);
            if ((replacement == null) || !isSectionWithKeysBelowIt(name, replacement, context)) {
                return replacement;
            }
            return replacement.withBindRestrictions(Bindable.BindRestriction.NO_DIRECT_PROPERTY);
        }

    }

}
