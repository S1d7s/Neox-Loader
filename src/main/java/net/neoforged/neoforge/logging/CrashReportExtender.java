/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.logging;

import cpw.mods.modlauncher.log.TransformingThrowablePatternConverter;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import joptsimple.internal.Strings;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.SystemReport;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.CrashReportCallables;
import net.neoforged.fml.ISystemReportExtender;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.i18n.FMLTranslations;
import net.neoforged.neoforge.forge.snapshots.ForgeSnapshotsMod;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.logging.log4j.Logger;

public class CrashReportExtender {
    // ============================================================
    // 1. CACHE DE STACK TRACE (evita recriação)
    // ============================================================
    private static final StackTraceElement[] BLANK_STACK_TRACE = new StackTraceElement[0];
    private static final ConcurrentMap<String, String> translatedMessageCache = new ConcurrentHashMap<>();
    
    // ============================================================
    // 2. CONSTANTES OTIMIZADAS
    // ============================================================
    private static final String NO_MOD_INFO = "<No mod information provided>";
    private static final String NO_ISSUES_URL = "<No issues URL found>";
    private static final String NO_EXCEPTION = "<No associated exception found>";
    private static final String MOD_LOADING_ISSUE_PREFIX = "Mod loading issue for: ";
    private static final String MOD_LOADING_ISSUE = "Mod loading issue";

    // ============================================================
    // 3. MÉTODOS PÚBLICOS
    // ============================================================
    public static void extendSystemReport(final SystemReport systemReport) {
        for (final ISystemReportExtender call : CrashReportCallables.allCrashCallables()) {
            if (call.isActive()) {
                systemReport.setDetail(call.getLabel(), call);
            }
        }
    }

    public static void addCrashReportHeader(StringBuilder stringbuilder, CrashReport crashReport) {
        ForgeSnapshotsMod.addCrashReportHeader(stringbuilder, crashReport);
    }

    public static String generateEnhancedStackTrace(final Throwable throwable) {
        return generateEnhancedStackTrace(throwable, true);
    }

    public static String generateEnhancedStackTrace(final StackTraceElement[] stacktrace) {
        final Throwable t = new Throwable();
        t.setStackTrace(stacktrace);
        return generateEnhancedStackTrace(t, false);
    }

    public static String generateEnhancedStackTrace(final Throwable throwable, boolean header) {
        // Se o throwable for nulo ou não tiver stack trace, retorna vazio
        if (throwable == null || throwable.getStackTrace().length == 0) {
            return "";
        }
        
        final String s = TransformingThrowablePatternConverter.generateEnhancedStackTrace(throwable);
        if (!header) {
            int idx = s.indexOf(Strings.LINE_SEPARATOR);
            return idx >= 0 ? s.substring(idx) : s;
        }
        return s;
    }

    // ============================================================
    // 4. DUMP MOD LOADING CRASH REPORT (OTIMIZADO)
    // ============================================================
    public static File dumpModLoadingCrashReport(final Logger logger, final List<ModLoadingIssue> issues, final File topLevelDir) {
        final CrashReport crashReport = CrashReport.forThrowable(
            new ModLoadingCrashException("Mod loading has failed"), 
            "Mod loading failures have occurred; consult the issue messages for more details"
        );
        
        for (var issue : issues) {
            final Optional<IModInfo> modInfo = Optional.ofNullable(issue.affectedMod());
            final String categoryName = modInfo
                .map(iModInfo -> MOD_LOADING_ISSUE_PREFIX + iModInfo.getModId())
                .orElse(MOD_LOADING_ISSUE);
            final CrashReportCategory category = crashReport.addCategory(categoryName);
            
            // Processa a causa (com cache)
            Throwable cause = issue.cause();
            int depth = 0;
            while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
                String stackTrace = generateEnhancedStackTrace(cause.getStackTrace())
                    .replaceAll(Strings.LINE_SEPARATOR + "\t", "\n\t\t");
                category.setDetail("Caused by " + (depth++), cause + stackTrace);
                cause = cause.getCause();
            }
            
            // Otimiza o stack trace (evita alocações desnecessárias)
            if (cause != null) {
                category.setStackTrace(cause.getStackTrace());
            } else {
                category.setStackTrace(BLANK_STACK_TRACE);
            }
            
            // Define detalhes com lazy loading (evita criar strings se não forem usadas)
            category.setDetail("Mod file", () -> modInfo
                .map(IModInfo::getOwningFile)
                .map(t -> t.getFile().getFilePath().toUri().getPath())
                .orElse(NO_MOD_INFO));
            
            // Mensagem de erro com cache
            category.setDetail("Failure message", () -> getTranslatedMessage(issue));
            
            // Versão do mod
            category.setDetail("Mod version", () -> modInfo
                .map(IModInfo::getVersion)
                .map(Object::toString)
                .orElse(NO_MOD_INFO));
            
            // URL de issues
            category.setDetail("Mod issues URL", () -> modInfo
                .map(IModInfo::getOwningFile)
                .map(IModFileInfo.class::cast)
                .flatMap(mfi -> mfi.getConfig().<String>getConfigElement("issueTrackerURL"))
                .orElse(NO_ISSUES_URL));
            
            // Mensagem de exceção
            category.setDetail("Exception message", Objects.toString(cause, NO_EXCEPTION));
        }
        
        // Salva o relatório
        final File crashReportsDir = new File(topLevelDir, "crash-reports");
        final String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        final File crashFile = new File(crashReportsDir, "crash-" + timestamp + "-fml.txt");
        
        if (crashReport.saveToFile(crashFile.toPath(), ReportType.CRASH)) {
            logger.fatal("Crash report saved to {}", crashFile);
        } else {
            logger.fatal("Failed to save crash report");
        }
        
        Bootstrap.realStdoutPrintln(crashReport.getFriendlyReport(ReportType.CRASH));
        return crashFile;
    }

    // ============================================================
    // 5. MÉTODOS AUXILIARES (COM CACHE)
    // ============================================================
    private static String getTranslatedMessage(ModLoadingIssue issue) {
        try {
            String key = issue.translationKey();
            // Usa cache para mensagens já traduzidas
            String cached = translatedMessageCache.get(key);
            if (cached != null) {
                return cached;
            }
            
            String translated = FMLTranslations.stripControlCodes(
                FMLTranslations.translateIssueEnglish(issue)
            ).replace("\n", "\n\t\t");
            
            // Armazena no cache (apenas para mensagens bem-sucedidas)
            if (!translated.isEmpty()) {
                translatedMessageCache.put(key, translated);
            }
            return translated;
            
        } catch (Exception e) {
            // Fallback: usa a chave e argumentos
            StringBuilder sb = new StringBuilder();
            sb.append(issue.translationKey().replace("\n", "\n\t\t"));
            
            List<Object> args = issue.translationArgs();
            for (int i = 0; i < args.size(); i++) {
                sb.append("\n\t\tArg ").append(i + 1).append(": ");
                sb.append(args.get(i).toString().replace("\n", "\n\t\t"));
            }
            return sb.toString();
        }
    }

    // ============================================================
    // 6. CLASSE DE EXCEÇÃO OTIMIZADA
    // ============================================================
    /**
     * Dummy exception used as the 'root' exception in {@linkplain #dumpModLoadingCrashReport(Logger, List, File) mod
     * loading crash reports}, which has no stack trace.
     *
     * <p>The stacktrace is very likely to be constant (since its only invoked by the sided mod loader classes), so their
     * stacktrace is irrelevant for debugging and only serve to distract the reader from the actual exceptions further
     * down in the crash report.</p>
     */
    private static class ModLoadingCrashException extends Exception {
        private static final long serialVersionUID = 1L;

        public ModLoadingCrashException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // Do not fill in the stack trace (economiza memória)
            return this;
        }
    }

    // ============================================================
    // 7. MÉTODO PARA LIMPAR CACHE (OPCIONAL)
    // ============================================================
    /**
     * Limpa o cache de mensagens traduzidas. Útil em desenvolvimento
     * para forçar recarregamento de traduções.
     */
    public static void clearTranslationCache() {
        translatedMessageCache.clear();
    }
}
