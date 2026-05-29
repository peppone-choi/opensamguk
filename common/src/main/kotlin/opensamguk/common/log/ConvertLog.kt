package opensamguk.common.log

fun convertLog(value: String, type: Int = 1): String {
    if (value.isEmpty()) return ""
    var r = value
    if (type > 0) {
        r = r.replace("<1>", "<font size=1>"); r = r.replace("<Y1>", "<font size=1 color=yellow>")
        r = r.replace("<R>", "<font color=red>"); r = r.replace("<B>", "<font color=blue>")
        r = r.replace("<G>", "<font color=green>"); r = r.replace("<M>", "<font color=magenta>")
        r = r.replace("<C>", "<font color=cyan>"); r = r.replace("<L>", "<font color=limegreen>")
        r = r.replace("<S>", "<font color=skyblue>"); r = r.replace("<O>", "<font color=orangered>")
        r = r.replace("<D>", "<font color=orangered>"); r = r.replace("<Y>", "<font color=yellow>")
        r = r.replace("<W>", "<font color=white>"); r = r.replace("</>", "</font>")
        return r
    }
    for (tag in listOf("<1>","<Y1>","<R>","<B>","<G>","<M>","<C>","<L>","<S>","<O>","<D>","<Y>","<W>","</>")) r = r.replace(tag, "")
    return r
}
