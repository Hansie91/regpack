package dev.regpack.model.ident;

/**
 * The one-line flaws of the check digit algorithms, switched on through a package-private seam by
 * the mutant tests only (plan Convention 3: each stage has a mutant). Every public method of the
 * identifier classes runs with {@link #NONE}.
 */
enum Flaw {
  /** The algorithms as specified. */
  NONE,
  /** The Luhn doubling runs on the other digit positions. */
  LUHN_DOUBLING_FLIPPED,
  /** Every letter expands to a value one higher than it should. */
  LETTER_EXPANSION_SHIFTED,
  /** The modulus is one less (LEI) or one more (Luhn) than specified. */
  WRONG_MODULUS
}
