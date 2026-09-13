package com.cogistra.mcpbridge.sample;

/** Synthetic sample fact. owner is always assigned and checked by the server. */
public record SampleRecord(String id, String owner, long version, String title, String note) {}
