/**
 * The concrete throwables of the shared error vocabulary. The contract they carry — {@link dev.vaullet.common.error.ErrorType} and {@link dev.vaullet.common.error.ApplicationException} — lives one package up.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes, so a {@code @NullMarked} on
 * {@code .} would not reach this one. Spring Framework does the same —
 * {@code spring-core} ships fifty {@code package-info} classes, one per package.
 */
@NullMarked
package dev.vaullet.common.error.exception;

import org.jspecify.annotations.NullMarked;
