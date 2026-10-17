package opensamguk.engine.archprobe;

import opensamguk.infra.archprobe.A3DiagnosticTarget;

/** Bytecode fixtures: repeated edges and nested origins must not inflate origin counts. */
public class A3DiagnosticProbe {
    public static class FirstHandler {
        A3DiagnosticTarget first;
        A3DiagnosticTarget repeated;
        A3DiagnosticTarget.Other second;
        A3DiagnosticTarget.Third third;
    }

    public static class SecondHandler { A3DiagnosticTarget target; }
    public static class ThirdHandler { A3DiagnosticTarget target; }
    public static class FourthHandler { A3DiagnosticTarget target; }
    public static class FifthHandler { A3DiagnosticTarget target; }
    public static class CleanHandler { String value; }
}
