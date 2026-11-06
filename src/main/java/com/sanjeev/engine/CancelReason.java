package com.sanjeev.engine;

/** Why quantity was removed from an order without trading. */
public enum CancelReason {
    /** The owner asked for the cancel. */
    REQUESTED,
    /** An IOC or market order could not fill completely; the unfilled rest was dropped. */
    UNFILLED_REMAINDER,
    /** A FOK order could not be filled completely, so it was cancelled whole. */
    FOK_NOT_FILLABLE
}
