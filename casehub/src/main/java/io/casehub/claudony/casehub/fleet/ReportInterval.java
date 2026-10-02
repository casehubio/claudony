package io.casehub.claudony.casehub.fleet;

public sealed interface ReportInterval {
    record Turn() implements ReportInterval {}
    record Periodic(int turns) implements ReportInterval {}
    record Completion() implements ReportInterval {}
}
