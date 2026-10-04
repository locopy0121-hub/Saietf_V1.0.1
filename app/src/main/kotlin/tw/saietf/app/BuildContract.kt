package tw.saietf.app

object BuildContract {
    val includedModules: Set<String> = BuildConfig.INCLUDED_MODULES
        .split(',')
        .filter(String::isNotBlank)
        .toSet()
}
