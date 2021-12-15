/**
 *
 */
package com.i18next.java;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stan
 */
public class I18Next {
    static final Logger logger = LoggerFactory.getLogger(I18Next.class);

    private static final String PREF_KEY_I18N = "i18n_json";

    private static final String SEPARATOR_LANGUAGE_COUNTRY = "_";
    private static final String WRONG_SEPARATOR_LANGUAGE_COUNTRY = "_";

    private static final String COMMA = ",";

    private Options mOptions = new Options();
    private JSONObject mRootObject = new JSONObject();

    /**
     * SingletonHolder is loaded on the first execution of Singleton.getInstance()
     * or the first access to SingletonHolder.INSTANCE, not before.
     */
    private static class SingletonHolder {
        public static final I18Next INSTANCE = new I18Next();
    }

    public I18Next() {
    }

    public static I18Next getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public Loader loader() {
        return new Loader(this);
    }

    enum LogMode {
        VERBOSE, WARNING, ERROR;
    }

    public static boolean isI18NextKeyCandidate(CharSequence key) {
        if (key != null && key.length() > 0) {
            return key.toString().matches("([a-z0-9]+((\\_)([a-z0-9]+))*)+((\\.)[a-z0-9]+((\\_)([a-z0-9]+))*)+");
        } else {
            return false;
        }
    }

    public boolean isEmpty() {
        return mRootObject.length() == 0;
    }

    public Options getOptions() {
        return mOptions;
    }

    void log(String raw, Object... args) {
        log(LogMode.VERBOSE, raw, args);
    }

    void log(LogMode logMode, String raw, Object... args) {
        if (mOptions.isDebugMode()) {
            if (args != null) {
                raw = String.format(raw, args);
            }
            switch (logMode) {
                case ERROR:
                	logger.error(raw);
                    break;
                case WARNING:
                	logger.warn(raw);
                    break;
                case VERBOSE:
                	logger.debug(raw);
                    break;

            }
        }
    }

    private String[] splitKeyPath(String key) {
        if (key != null) {
            String[] splitKeys = key.split(Pattern.quote(mOptions.getKeySeparator()));
            if (splitKeys == null) {
                log(LogMode.ERROR, "impossible to split key '%s'", key);
            }
            return splitKeys;
        }
        return null;
    }

    private String getNamespace(String key) {
        if (key != null) {
            int indexOfNS = key.indexOf(mOptions.getNsSeparator());
            if (indexOfNS > 0) {
                String namespace = key.substring(0, indexOfNS);
                log("namespace found for key '%s': %s", key, namespace);
                return namespace;
            }
        }
        return mOptions.getDefaultNamespace();
    }

    private void initDefaultNamespaceIfNeeded(String nameSpace) {
        if (mOptions.getDefaultNamespace() == null) {
            log("namespace taken from the first key available (it's now our default namespace): %s", nameSpace);
            mOptions.setDefaultNamespace(nameSpace);
        }
    }

    public String t(String key) {
        return t(key, null);
    }

    /**
     * Using multiple keys (first found will be translated)
     *
     * @param keys
     * @return
     */
    public String t(String... keys) {
        return t(keys, null);
    }

    public String t(String key, Operation operation) {
        String[] keys = {key};
        return t(keys, operation);
    }

    public String t(String[] keys, Operation operation) {
        String innerProcessValue = null;
        if (keys != null && keys.length > 0) {
            for (String key : keys) {
                String rawValue = getValueRaw(key, operation);
                innerProcessValue = transformRawValue(operation, rawValue);
                if (innerProcessValue != null) {
                    break;
                } else if (mOptions.isDebugMode()) {
                    log(LogMode.WARNING, "impossible to found key '%s'", key);
                }
            }
        }
        return innerProcessValue;
    }

    private String transformRawValue(Operation operation, String rawValue) {
        String innerProcessValue;
        if (operation instanceof Operation.PostOperation) {
            rawValue = ((Operation.PostOperation) operation).postProcess(rawValue);
        }
        String rawValueNestingReplaced = getRawWithNestingReplaced(rawValue, operation);
        if (rawValueNestingReplaced != null) {
            innerProcessValue = transformRawValue(operation, rawValueNestingReplaced);
        } else {
            innerProcessValue = rawValue;
        }
        return innerProcessValue;
    }

    public boolean existValue(String key) {
        return getValueRaw(key, null) != null;
    }

    private String getValueRaw(String key, Operation operation) {
        if (key == null) {
            return null;
        }
        String value = null;
        String namespace = getNamespace(key);
        if (namespace != null) {
            if (key.startsWith(namespace)) {
                key = key.substring(namespace.length() + 1); // +1 for the colon
            }
            if (operation instanceof Operation.PreOperation) {
                // it's the last key part
                key = ((Operation.PreOperation) operation).preProcess(key);
            }

            value = getValueRawWithoutPreprocessing(namespace, key);

            if (value == null && operation instanceof Operation.PreOperation) {
                String repreProcessedKey = ((Operation.PreOperation) operation).preProcessAfterNoValueFound(key);
                if (repreProcessedKey != null && !repreProcessedKey.equals(key)) {
                    value = getValueRawWithoutPreprocessing(namespace, repreProcessedKey);
                }
            }
        }
        return value;
    }

    private String getValueRawWithoutPreprocessing(String namespace, String key) {
        String value;

        String[] splitKeys = splitKeyPath(key);
        if (splitKeys == null) {
            value = null;
        } else {
            value = getValueRawByLanguageWithNamespace(mOptions.getLanguage(), namespace, splitKeys);
            if (value == null) {
                value = getValueRawByLanguageWithNamespace(mOptions.getFallbackLanguage(), namespace, splitKeys);
            }
        }
        return value;
    }

    private String getValueRawByLanguageWithNamespace(String lang, String namespace, String[] splitKeys) {
        JSONObject rootObject = getRootObjectByLang(lang);
        if (rootObject != null) {
            Object o = rootObject.opt(namespace);
            for (int i = 0; i < splitKeys.length; i++) {
                String splitKey = splitKeys[i];
                if (o instanceof JSONObject) {
                    o = ((JSONObject) o).opt(splitKey);
                } else {
                    o = null;
                    break;
                }
            }
            if (o instanceof String) {
                return (String) o;
            }
        }
        return null;
    }

    private JSONObject getRootObjectByLang(String lang) {
        JSONObject result = null;
        if (lang != null) {
            result = mRootObject.optJSONObject(lang);
            if (result == null) {
                int indexOfLangSeparator = lang.lastIndexOf(SEPARATOR_LANGUAGE_COUNTRY);
                if (indexOfLangSeparator > 0) {
                    // found a separator
                    result = getRootObjectByLang(lang.substring(0, indexOfLangSeparator));
                }
            }
        }
        return result;
    }

    static String getConvertLang(String lang) {
        if (lang.contains(WRONG_SEPARATOR_LANGUAGE_COUNTRY)) {
            lang = lang.replaceAll(WRONG_SEPARATOR_LANGUAGE_COUNTRY, SEPARATOR_LANGUAGE_COUNTRY);
        }
        return lang;
    }

    private String getRawWithNestingReplaced(String raw, Operation operation) {
        // nesting
        String reusePrefix = mOptions.getReusePrefix();
        String reuseSuffix = mOptions.getReuseSuffix();
        if (raw != null && raw.length() > 0 && reusePrefix != null && reuseSuffix != null && reusePrefix.length() > 0 && reuseSuffix.length() > 0) {
            int indexOfPrefix = raw.indexOf(reusePrefix);
            if (indexOfPrefix >= 0) {
                int indexOfSuffix = raw.indexOf(reuseSuffix, indexOfPrefix);
                if (indexOfSuffix > 0) {
                    // we've found a prefix and a suffix
                    String param = raw.substring(indexOfPrefix, indexOfSuffix + reuseSuffix.length());
                    String paramTrim = param.substring(reusePrefix.length(), indexOfSuffix - indexOfPrefix);

                    // nested replacement
                    int commaIndex = paramTrim.indexOf(COMMA);
                    while (commaIndex > 0 && paramTrim.length() > commaIndex + 1) {
                        String textLeft = paramTrim.substring(commaIndex + 1);
                        try {
                            JSONObject jsonObject = new JSONObject(textLeft);
                            String countParam = jsonObject.optString("count");
                            if (countParam.length() > 0) {
                                String countParamWithReplace
                                        = getRawWithNestingReplaced(countParam, operation);
                                if (countParamWithReplace != null) {
                                    countParam = countParamWithReplace;
                                }
                                paramTrim = paramTrim.substring(0, commaIndex);
                                try {
                                    Operation.Plural replacePlural;
                                    try {
                                        replacePlural
                                                = new Operation.Plural(Integer.parseInt(countParam));
                                    } catch (NumberFormatException ex) {
                                        replacePlural
                                                = new Operation.Plural(Float.parseFloat(countParam));
                                    }
                                    if (operation == null) {
                                        operation = replacePlural;
                                    } else {
                                        operation = new Operation.MultiPostProcessing(
                                                replacePlural, operation);
                                    }
                                    break;
                                } catch (NumberFormatException ex) {
                                }
                            }
                        } catch (JSONException e) {
                            commaIndex = paramTrim.indexOf(COMMA, commaIndex + 1);
                        }
                    }

                    String replacement = t(paramTrim, operation);
                    if (replacement == null) {
                        replacement = "";
                    }
                    int hashBefore = raw.hashCode();
                    raw = raw.replace(param, replacement);
                    if (hashBefore != raw.hashCode()) {
                        // the string has been changed, try to change it again
                        String rawWithSubReplacement = getRawWithNestingReplaced(raw, operation);
                        if (rawWithSubReplacement != null) {
                            raw = rawWithSubReplacement;
                        }
                    }
                    return raw;
                }
            }
        }
        return null;
    }


    private void load(String lang, String namespace, JSONObject json) throws JSONException {
        JSONObject rootLanguage = mRootObject.optJSONObject(getConvertLang(lang));
        if (rootLanguage == null) {
            rootLanguage = new JSONObject();
            mRootObject.put(lang, rootLanguage);
        }
        rootLanguage.put(namespace, json);
        initDefaultNamespaceIfNeeded(namespace);
    }

    static boolean equalsCharSequence(CharSequence cs, CharSequence cs2) {
        return (cs2 == null && cs == null) || (cs != null && cs.equals(cs2));
    }

    public static class Loader {

        private JSONObject mJSONObject;
        private String mNameSpace;
        private String mLang;
        private I18Next mI18Next;

        public Loader(I18Next i18Next) {
            mI18Next = i18Next;
        }

        public Loader from(String json) throws JSONException {
            return from(new JSONObject(json));
        }

        public Loader from(JSONObject jsonObject) {
            mJSONObject = jsonObject;
            return this;
        }

        public Loader namespace(String nameSpace) {
            mNameSpace = nameSpace;
            return this;
        }

        public Loader lang(String lang) {
            mLang = lang;
            return this;
        }

        public void load() throws JSONException {
            if (mLang == null) {
                mLang = mI18Next.mOptions.getLanguage();
            }
            if (mNameSpace == null) {
                mNameSpace = mI18Next.mOptions.getDefaultNamespace();
                if (mNameSpace == null) {
                    mNameSpace = "DEFAULT_NAMESPACE";
                }
            }
            mI18Next.load(mLang, mNameSpace, mJSONObject);
        }
    }
}
