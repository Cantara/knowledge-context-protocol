package no.cantara.kcp.model;

/**
 * Authority block declaring action permissions for a knowledge unit or manifest default.
 * See SPEC.md §RFC-0009 (v0.12).
 *
 * <p>Each action value is one of: {@code initiative} | {@code requires_approval} | {@code denied}.
 *
 * <p>Safe defaults: {@code read} = initiative, {@code summarize} = initiative,
 * all others = denied.
 *
 * @param read            Permission to read the unit. Default: {@code initiative}.
 * @param summarize       Permission to summarize the unit. Default: {@code initiative}.
 * @param modify          Permission to modify the unit. Default: {@code denied}.
 * @param shareExternally Permission to share the unit externally. Default: {@code denied}.
 * @param execute         Permission to execute the unit (if kind=executable). Default: {@code denied}.
 */
public record Authority(
        String read,
        String summarize,
        String modify,
        String shareExternally,
        String execute
) {}
