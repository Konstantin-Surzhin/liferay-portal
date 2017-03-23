/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.source.formatter;

import com.liferay.portal.kernel.io.unsync.UnsyncStringReader;
import com.liferay.portal.kernel.nio.charset.CharsetDecoderUtil;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.CharPool;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.NaturalOrderStringComparator;
import com.liferay.portal.kernel.util.ReflectionUtil;
import com.liferay.portal.kernel.util.ReleaseInfo;
import com.liferay.portal.kernel.util.SetUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.TextFormatter;
import com.liferay.portal.kernel.util.Tuple;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.tools.ToolsUtil;
import com.liferay.portal.xml.SAXReaderFactory;
import com.liferay.source.formatter.checks.FileCheck;
import com.liferay.source.formatter.util.FileUtil;

import java.awt.Desktop;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.lang.reflect.Field;

import java.net.URI;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.tools.ant.types.selectors.SelectorUtils;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;

/**
 * @author Brian Wing Shun Chan
 * @author Igor Spasic
 * @author Wesley Gong
 * @author Hugo Huijser
 */
public abstract class BaseSourceProcessor implements SourceProcessor {

	public static final int PLUGINS_MAX_DIR_LEVEL =
		ToolsUtil.PLUGINS_MAX_DIR_LEVEL;

	public static final int PORTAL_MAX_DIR_LEVEL =
		ToolsUtil.PORTAL_MAX_DIR_LEVEL;

	@Override
	public final void format() throws Exception {
		if (sourceFormatterArgs.isShowDocumentation()) {
			System.setProperty("java.awt.headless", "false");
		}

		List<String> fileNames = getFileNames();

		if (fileNames.isEmpty()) {
			return;
		}

		preFormat();

		populateFileChecks();

		ExecutorService executorService = Executors.newFixedThreadPool(
			sourceFormatterArgs.getProcessorThreadCount());

		List<Future<Void>> futures = new ArrayList<>(fileNames.size());

		for (final String fileName : fileNames) {
			Future<Void> future = executorService.submit(
				new Callable<Void>() {

					@Override
					public Void call() throws Exception {
						try {
							format(fileName);

							return null;
						}
						catch (Exception e) {
							throw new RuntimeException(
								"Unable to format " + fileName, e);
						}
					}

				});

			futures.add(future);
		}

		for (Future<Void> future : futures) {
			future.get();
		}

		executorService.shutdown();

		postFormat();

		_sourceFormatterHelper.close();
	}

	public final List<String> getFileNames() throws Exception {
		List<String> fileNames = sourceFormatterArgs.getFileNames();

		if (fileNames != null) {
			return fileNames;
		}

		return doGetFileNames();
	}

	@Override
	public SourceMismatchException getFirstSourceMismatchException() {
		return _firstSourceMismatchException;
	}

	@Override
	public String[] getIncludes() {
		return filterIncludes(doGetIncludes());
	}

	@Override
	public List<String> getModifiedFileNames() {
		return _modifiedFileNames;
	}

	@Override
	public Set<SourceFormatterMessage> getSourceFormatterMessages() {
		Set<SourceFormatterMessage> sourceFormatterMessages =
			new TreeSet<>();

		for (Map.Entry<String, Set<SourceFormatterMessage>> entry :
				_sourceFormatterMessagesMap.entrySet()) {

			sourceFormatterMessages.addAll(entry.getValue());
		}

		return sourceFormatterMessages;
	}

	@Override
	public void processMessage(String fileName, String message) {
		processMessage(fileName, message, -1);
	}

	@Override
	public void processMessage(String fileName, String message, int lineCount) {
		processMessage(fileName, message, null, lineCount);
	}

	@Override
	public void processMessage(
		String fileName, String message, String markdownFileName) {

		processMessage(fileName, message, markdownFileName, -1);
	}

	@Override
	public void processMessage(
		String fileName, String message, String markdownFileName,
		int lineCount) {

		processMessage(
			fileName,
			new SourceFormatterMessage(
				fileName, message, markdownFileName, lineCount));
	}

	@Override
	public void setProperties(Properties properties) {
		_properties = properties;
	}

	@Override
	public void setSourceFormatterArgs(
		SourceFormatterArgs sourceFormatterArgs) {

		this.sourceFormatterArgs = sourceFormatterArgs;

		_init();
	}

	protected int adjustLevel(int level, String text, String s, int diff) {
		String[] lines = StringUtil.splitLines(text);

		forLoop:
		for (String line : lines) {
			line = StringUtil.trim(line);

			if (line.startsWith("//")) {
				continue;
			}

			int x = -1;

			while (true) {
				x = line.indexOf(s, x + 1);

				if (x == -1) {
					continue forLoop;
				}

				if (!ToolsUtil.isInsideQuotes(line, x)) {
					level += diff;
				}
			}
		}

		return level;
	}

	protected void checkEmptyCollection(
		String line, String fileName, int lineCount) {

		// LPS-46028

		Matcher matcher = emptyCollectionPattern.matcher(line);

		if (matcher.find()) {
			String collectionType = TextFormatter.format(
				matcher.group(1), TextFormatter.J);

			processMessage(
				fileName, "Use Collections.empty" + collectionType + "()",
				lineCount);
		}
	}

	protected void checkGetterUtilGet(String fileName, String content)
		throws Exception {

		Matcher matcher = getterUtilGetPattern.matcher(content);

		while (matcher.find()) {
			if (ToolsUtil.isInsideQuotes(content, matcher.start())) {
				continue;
			}

			List<String> parametersList = getParameterList(matcher.group());

			if (parametersList.size() != 2) {
				continue;
			}

			String defaultVariableName =
				"DEFAULT_" + StringUtil.toUpperCase(matcher.group(1));

			Field defaultValuefield = GetterUtil.class.getDeclaredField(
				defaultVariableName);

			String defaultValue = String.valueOf(defaultValuefield.get(null));

			String value = parametersList.get(1);

			if (value.equals("StringPool.BLANK")) {
				value = StringPool.BLANK;
			}

			if (Objects.equals(value, defaultValue)) {
				processMessage(
					fileName,
					"No need to pass default value '" + parametersList.get(1) +
						"'",
					getLineCount(content, matcher.start()));
			}
		}
	}

	protected void checkInefficientStringMethods(
		String line, String fileName, int lineCount) {

		String methodName = "toLowerCase";

		int pos = line.indexOf(".toLowerCase()");

		if (pos == -1) {
			methodName = "toUpperCase";

			pos = line.indexOf(".toUpperCase()");
		}

		if ((pos == -1) && !line.contains("StringUtil.equalsIgnoreCase(")) {
			methodName = "equalsIgnoreCase";

			pos = line.indexOf(".equalsIgnoreCase(");
		}

		if (pos != -1) {
			processMessage(fileName, "Use StringUtil." + methodName, lineCount);
		}
	}

	protected void checkInefficientStringMethods(
		String line, String fileName, String absolutePath, int lineCount,
		boolean javaSource) {

		if (isExcludedPath(RUN_OUTSIDE_PORTAL_EXCLUDES, absolutePath)) {
			return;
		}

		if (javaSource) {
			checkInefficientStringMethods(line, fileName, lineCount);

			return;
		}

		Matcher matcher = javaSourceInsideJSPLinePattern.matcher(line);

		while (matcher.find()) {
			checkInefficientStringMethods(
				matcher.group(1), fileName, lineCount);
		}
	}

	protected String checkPrincipalException(String content) {
		String newContent = content;

		Matcher matcher = principalExceptionPattern.matcher(content);

		while (matcher.find()) {
			String match = matcher.group();

			String replacement = StringUtil.replace(
				match, "class.getName", "getNestedClasses");

			newContent = StringUtil.replace(newContent, match, replacement);
		}

		return newContent;
	}

	protected void checkPropertyUtils(String fileName, String content) {
		if (fileName.endsWith("TypeConvertorUtil.java")) {
			return;
		}

		if (content.contains("org.apache.commons.beanutils.PropertyUtils")) {
			processMessage(
				fileName,
				"Do not use org.apache.commons.beanutils.PropertyUtils, see " +
					"LPS-62786");
		}
	}

	protected void checkResourceUtil(
		String line, String fileName, String absolutePath, int lineCount) {

		if ((!portalSource && !subrepository) ||
			fileName.endsWith("ResourceBundleUtil.java") ||
			isExcludedPath(RUN_OUTSIDE_PORTAL_EXCLUDES, absolutePath)) {

			return;
		}

		if (line.contains("ResourceBundle.getBundle(")) {
			processMessage(
				fileName,
				"Use ResourceBundleUtil.getBundle instead of " +
					"ResourceBundle.getBundle, see LPS-58529",
				lineCount);
		}

		if (line.contains("resourceBundle.getString(")) {
			processMessage(
				fileName,
				"Use ResourceBundleUtil.getString instead of " +
					"resourceBundle.getString, see LPS-58529",
				lineCount);
		}
	}

	protected void checkStringUtilReplace(String fileName, String content)
		throws Exception {

		Matcher matcher = stringUtilReplacePattern.matcher(content);

		while (matcher.find()) {
			if (ToolsUtil.isInsideQuotes(content, matcher.start())) {
				continue;
			}

			List<String> parametersList = getParameterList(matcher.group());

			if (parametersList.size() != 3) {
				return;
			}

			String secondParameter = parametersList.get(1);

			Matcher singleLengthMatcher = singleLengthStringPattern.matcher(
				secondParameter);

			if (!singleLengthMatcher.find()) {
				continue;
			}

			String fieldName = singleLengthMatcher.group(2);

			if (fieldName != null) {
				Field field = StringPool.class.getDeclaredField(fieldName);

				String value = (String)field.get(null);

				if (value.length() != 1) {
					continue;
				}
			}

			String method = matcher.group(1);

			StringBundler sb = new StringBundler(5);

			sb.append("Use StringUtil.");
			sb.append(method);
			sb.append("(String, char, char) or StringUtil.");
			sb.append(method);
			sb.append("(String, char, String) instead");

			processMessage(
				fileName, sb.toString(),
				getLineCount(content, matcher.start()));
		}
	}

	protected void checkUTF8(File file, String fileName) throws Exception {
		byte[] bytes = FileUtil.getBytes(file);

		try {
			CharsetDecoder charsetDecoder =
				CharsetDecoderUtil.getCharsetDecoder(
					StringPool.UTF8, CodingErrorAction.REPORT);

			charsetDecoder.decode(ByteBuffer.wrap(bytes));
		}
		catch (Exception e) {
			processMessage(fileName, "UTF-8");
		}
	}

	protected abstract String doFormat(
			File file, String fileName, String absolutePath, String content)
		throws Exception;

	protected abstract List<String> doGetFileNames() throws Exception;

	protected abstract String[] doGetIncludes();

	protected String[] filterIncludes(String[] includes) {
		List<String> fileExtensions = sourceFormatterArgs.getFileExtensions();

		if (fileExtensions.isEmpty()) {
			return includes;
		}

		String[] filteredIncludes = new String[0];

		for (String include : includes) {
			for (String fileExtension : fileExtensions) {
				if (include.endsWith(fileExtension)) {
					filteredIncludes = ArrayUtil.append(
						filteredIncludes, include);
				}
			}
		}

		return filteredIncludes;
	}

	protected String fixCompatClassImports(String absolutePath, String content)
		throws Exception {

		if (portalSource || subrepository || !_usePortalCompatImport ||
			absolutePath.contains("/ext-") ||
			absolutePath.contains("/portal-compat-shared/")) {

			return content;
		}

		Map<String, String> compatClassNamesMap = getCompatClassNamesMap();

		String newContent = content;

		for (Map.Entry<String, String> entry : compatClassNamesMap.entrySet()) {
			String compatClassName = entry.getKey();
			String extendedClassName = entry.getValue();

			Pattern pattern = Pattern.compile(extendedClassName + "\\W");

			while (true) {
				Matcher matcher = pattern.matcher(newContent);

				if (!matcher.find()) {
					break;
				}

				newContent =
					newContent.substring(0, matcher.start()) + compatClassName +
						newContent.substring(matcher.end() - 1);
			}
		}

		return newContent;
	}

	protected String fixCopyright(
			String content, String absolutePath, String fileName,
			String className)
		throws IOException {

		if (_copyright == null) {
			_copyright = getContent(
				sourceFormatterArgs.getCopyrightFileName(),
				PORTAL_MAX_DIR_LEVEL);
		}

		String copyright = _copyright;

		if (fileName.endsWith(".tpl") || fileName.endsWith(".vm") ||
			Validator.isNull(copyright)) {

			return content;
		}

		if (_oldCopyright == null) {
			_oldCopyright = getContent(
				"old-copyright.txt", PORTAL_MAX_DIR_LEVEL);
		}

		if (Validator.isNotNull(_oldCopyright) &&
			content.contains(_oldCopyright)) {

			content = StringUtil.replace(content, _oldCopyright, copyright);

			processMessage(fileName, "File contains old copyright information");
		}

		if (!content.contains(copyright)) {
			String customCopyright = getCustomCopyright(absolutePath);

			if (Validator.isNotNull(customCopyright)) {
				copyright = customCopyright;
			}

			if (!content.contains(copyright)) {
				processMessage(fileName, "(c)");
			}
			else if (!content.startsWith(copyright) &&
					 !content.startsWith("<%--\n" + copyright)) {

				processMessage(fileName, "File must start with copyright");
			}
		}
		else if (!content.startsWith(copyright) &&
				 !content.startsWith("<%--\n" + copyright)) {

			processMessage(fileName, "File must start with copyright");
		}

		if (fileName.endsWith(".jsp") || fileName.endsWith(".jspf")) {
			content = StringUtil.replace(
				content, "<%\n" + copyright + "\n%>",
				"<%--\n" + copyright + "\n--%>");
		}

		int x = content.indexOf("* Copyright (c) 2000-");

		if (x == -1) {
			return content;
		}

		int y = content.indexOf("Liferay", x);

		String contentCopyrightYear = content.substring(x, y);

		x = copyright.indexOf("* Copyright (c) 2000-");

		if (x == -1) {
			return content;
		}

		y = copyright.indexOf("Liferay", x);

		String copyrightYear = copyright.substring(x, y);

		return StringUtil.replace(content, contentCopyrightYear, copyrightYear);
	}

	protected String fixIncorrectParameterTypeForLanguageUtil(
		String content, boolean autoFix, String fileName) {

		if (portalSource || subrepository) {
			return content;
		}

		String expectedParameter = getProperty(
			"languageutil.expected.parameter");
		String incorrectParameter = getProperty(
			"languageutil.incorrect.parameter");

		if (!content.contains(
				"LanguageUtil.format(" + incorrectParameter + ", ") &&
			!content.contains(
				"LanguageUtil.get(" + incorrectParameter + ", ")) {

			return content;
		}

		if (autoFix) {
			content = StringUtil.replace(
				content,
				new String[] {
					"LanguageUtil.format(" + incorrectParameter + ", ",
					"LanguageUtil.get(" + incorrectParameter + ", "
				},
				new String[] {
					"LanguageUtil.format(" + expectedParameter + ", ",
					"LanguageUtil.get(" + expectedParameter + ", "
				});
		}
		else {
			processMessage(
				fileName,
				"(Unicode)LanguageUtil.format/get methods require " +
					expectedParameter + " parameter instead of " +
						incorrectParameter);
		}

		return content;
	}

	protected String fixSessionKey(
		String fileName, String content, Pattern pattern) {

		Matcher matcher = pattern.matcher(content);

		if (!matcher.find()) {
			return content;
		}

		String newContent = content;

		do {
			String match = matcher.group();

			String s = null;

			if (pattern.equals(sessionKeyPattern)) {
				s = StringPool.COMMA;
			}
			else if (pattern.equals(taglibSessionKeyPattern)) {
				s = "key=";
			}

			int x = match.indexOf(s);

			if (x == -1) {
				continue;
			}

			x = x + s.length();

			String substring = match.substring(x).trim();

			String quote = StringPool.BLANK;

			if (substring.startsWith(StringPool.APOSTROPHE)) {
				quote = StringPool.APOSTROPHE;
			}
			else if (substring.startsWith(StringPool.QUOTE)) {
				quote = StringPool.QUOTE;
			}
			else {
				continue;
			}

			int y = match.indexOf(quote, x);
			int z = match.indexOf(quote, y + 1);

			if ((y == -1) || (z == -1)) {
				continue;
			}

			String prefix = match.substring(0, y + 1);
			String suffix = match.substring(z);
			String oldKey = match.substring(y + 1, z);

			boolean alphaNumericKey = true;

			for (char c : oldKey.toCharArray()) {
				if (!Validator.isChar(c) && !Validator.isDigit(c) &&
					(c != CharPool.DASH) && (c != CharPool.UNDERLINE)) {

					alphaNumericKey = false;
				}
			}

			if (!alphaNumericKey) {
				continue;
			}

			String newKey = TextFormatter.format(oldKey, TextFormatter.O);

			newKey = TextFormatter.format(newKey, TextFormatter.M);

			if (newKey.equals(oldKey)) {
				continue;
			}

			String oldSub = prefix.concat(oldKey).concat(suffix);
			String newSub = prefix.concat(newKey).concat(suffix);

			newContent = StringUtil.replaceFirst(newContent, oldSub, newSub);
		}
		while (matcher.find());

		return newContent;
	}

	protected String fixUnparameterizedClassType(String content) {
		Matcher matcher = unparameterizedClassTypePattern1.matcher(content);

		if (matcher.find()) {
			return StringUtil.replaceFirst(
				content, "Class", "Class<?>", matcher.start());
		}

		matcher = unparameterizedClassTypePattern2.matcher(content);

		if (matcher.find()) {
			return StringUtil.replaceFirst(
				content, "Class", "Class<?>", matcher.start());
		}

		return content;
	}

	protected final String format(
			File file, String fileName, String absolutePath, String content)
		throws Exception {

		_sourceFormatterMessagesMap.remove(fileName);

		checkUTF8(file, fileName);

		if (!(this instanceof JavaSourceProcessor) &&
			absolutePath.matches(".*\\/modules\\/.*\\/src\\/.*\\/java\\/.*")) {

			processMessage(
				fileName, "Only *.java files are allowed in /src/*/java/");
		}

		String newContent = processFileChecks(fileName, absolutePath, content);

		newContent = doFormat(file, fileName, absolutePath, newContent);

		newContent = StringUtil.replace(
			newContent, StringPool.RETURN, StringPool.BLANK);

		if (content.equals(newContent)) {
			return content;
		}

		return format(file, fileName, absolutePath, newContent);
	}

	protected final void format(String fileName) throws Exception {
		if (!_isMatchPath(fileName)) {
			return;
		}

		fileName = StringUtil.replace(
			fileName, CharPool.BACK_SLASH, CharPool.SLASH);

		String absolutePath = getAbsolutePath(fileName);

		File file = new File(absolutePath);

		String content = FileUtil.read(file);

		String newContent = format(file, fileName, absolutePath, content);

		processFormattedFile(file, fileName, content, newContent);
	}

	protected String formatDefinitionKey(
		String fileName, String content, String definitionKey) {

		return content;
	}

	protected String formatEmptyArray(String line) {
		Matcher matcher = emptyArrayPattern.matcher(line);

		while (matcher.find()) {
			if (ToolsUtil.isInsideQuotes(line, matcher.end(1))) {
				continue;
			}

			String replacement = StringUtil.replace(
				matcher.group(1), "[]", "[0]");

			return StringUtil.replaceFirst(
				line, matcher.group(), replacement, matcher.start());
		}

		return line;
	}

	protected String formatJavaTerms(
			String javaClassName, String packagePath, File file,
			String fileName, String absolutePath, String content,
			String javaClassContent, int javaClassLineCount, String indent,
			String checkJavaFieldTypesExcludesProperty,
			String javaTermSortExcludesProperty,
			String testAnnotationsExcludesProperty)
		throws Exception {

		JavaSourceProcessor javaSourceProcessor = null;

		if (this instanceof JavaSourceProcessor) {
			javaSourceProcessor = (JavaSourceProcessor)this;
		}
		else {
			javaSourceProcessor = new JavaSourceProcessor();

			javaSourceProcessor.setProperties(_properties);
			javaSourceProcessor.setSourceFormatterArgs(sourceFormatterArgs);
		}

		JavaClass javaClass = new JavaClass(
			javaClassName, packagePath, file, fileName, absolutePath, content,
			javaClassContent, javaClassLineCount, indent + StringPool.TAB, null,
			javaSourceProcessor);

		String newJavaClassContent = javaClass.formatJavaTerms(
			getAnnotationsExclusions(), getImmutableFieldTypes(),
			checkJavaFieldTypesExcludesProperty, javaTermSortExcludesProperty,
			testAnnotationsExcludesProperty);

		if (!javaClassContent.equals(newJavaClassContent)) {
			return StringUtil.replaceFirst(
				content, javaClassContent, newJavaClassContent);
		}

		return content;
	}

	protected String formatStringBundler(
		String fileName, String content, int maxLineLength) {

		Matcher matcher = sbAppendPattern.matcher(content);

		matcherIteration:
		while (matcher.find()) {
			String appendValue = stripQuotes(matcher.group(2), CharPool.QUOTE);

			appendValue = StringUtil.replace(appendValue, "+\n", "+ ");

			if (!appendValue.contains(" + ")) {
				continue;
			}

			String[] appendValueParts = StringUtil.split(appendValue, " + ");

			for (String appendValuePart : appendValueParts) {
				if ((getLevel(appendValuePart) != 0) ||
					Validator.isNumber(appendValuePart)) {

					continue matcherIteration;
				}
			}

			processMessage(
				fileName, "Incorrect use of '+' inside StringBundler",
				getLineCount(content, matcher.start(1)));
		}

		matcher = sbAppendWithStartingSpacePattern.matcher(content);

		while (matcher.find()) {
			String firstLine = matcher.group(1);

			if (firstLine.endsWith("\\n\");")) {
				continue;
			}

			if ((maxLineLength != -1) &&
				(getLineLength(firstLine) >= maxLineLength)) {

				processMessage(
					fileName,
					"Do not append string starting with space to StringBundler",
					getLineCount(content, matcher.start(3)));
			}
			else {
				content = StringUtil.replaceFirst(
					content, "\");\n", " \");\n", matcher.start(2));
				content = StringUtil.replaceFirst(
					content, "(\" ", "(\"", matcher.start(3));
			}
		}

		return content;
	}

	protected String getAbsolutePath(String fileName) {
		Path filePath = Paths.get(fileName);

		filePath = filePath.toAbsolutePath();

		filePath = filePath.normalize();

		return StringUtil.replace(
			filePath.toString(), CharPool.BACK_SLASH, CharPool.SLASH);
	}

	protected Set<String> getAnnotationsExclusions() {
		if (_annotationsExclusions != null) {
			return _annotationsExclusions;
		}

		_annotationsExclusions = SetUtil.fromArray(
			new String[] {
				"ArquillianResource", "Autowired", "BeanReference", "Captor",
				"Inject", "Mock", "Parameter", "Reference", "ServiceReference",
				"SuppressWarnings"
			});

		return _annotationsExclusions;
	}

	protected BNDSettings getBNDSettings(String fileName) throws Exception {
		for (Map.Entry<String, BNDSettings> entry :
				_bndSettingsMap.entrySet()) {

			String bndFileLocation = entry.getKey();

			if (fileName.startsWith(bndFileLocation)) {
				return entry.getValue();
			}
		}

		String bndFileLocation = fileName;

		while (true) {
			int pos = bndFileLocation.lastIndexOf(StringPool.SLASH);

			if (pos == -1) {
				return null;
			}

			bndFileLocation = bndFileLocation.substring(0, pos + 1);

			File file = new File(bndFileLocation + "bnd.bnd");

			if (file.exists()) {
				return new BNDSettings(bndFileLocation, FileUtil.read(file));
			}

			bndFileLocation = StringUtil.replaceLast(
				bndFileLocation, StringPool.SLASH, StringPool.BLANK);
		}
	}

	protected Map<String, BNDSettings> getBNDSettingsMap() {
		return _bndSettingsMap;
	}

	protected Map<String, String> getCompatClassNamesMap() throws Exception {
		if (_compatClassNamesMap != null) {
			return _compatClassNamesMap;
		}

		Map<String, String> compatClassNamesMap = new HashMap<>();

		String[] includes = new String[] {
			"**/portal-compat-shared/src/com/liferay/compat/**/*.java"
		};

		String basedir = sourceFormatterArgs.getBaseDirName();

		List<String> fileNames = new ArrayList<>();

		for (int i = 0; i < PLUGINS_MAX_DIR_LEVEL; i++) {
			File sharedDir = new File(basedir + "shared");

			if (sharedDir.exists()) {
				fileNames = getFileNames(basedir, new String[0], includes);

				break;
			}

			basedir = basedir + "../";
		}

		for (String fileName : fileNames) {
			File file = new File(fileName);

			String content = FileUtil.read(file);

			fileName = StringUtil.replace(
				fileName, CharPool.BACK_SLASH, CharPool.SLASH);

			fileName = StringUtil.replace(
				fileName, CharPool.SLASH, CharPool.PERIOD);

			int pos = fileName.indexOf("com.");

			String compatClassName = fileName.substring(pos);

			compatClassName = compatClassName.substring(
				0, compatClassName.length() - 5);

			String extendedClassName = StringUtil.replace(
				compatClassName, "compat.", StringPool.BLANK);

			if (content.contains("extends " + extendedClassName)) {
				compatClassNamesMap.put(compatClassName, extendedClassName);
			}
		}

		_compatClassNamesMap = compatClassNamesMap;

		return _compatClassNamesMap;
	}

	protected String getContent(String fileName, int level) throws IOException {
		File file = getFile(fileName, level);

		if (file != null) {
			String content = FileUtil.read(file);

			if (Validator.isNotNull(content)) {
				return content;
			}
		}

		return StringPool.BLANK;
	}

	protected String getCustomCopyright(String absolutePath)
		throws IOException {

		for (int x = absolutePath.length();;) {
			x = absolutePath.lastIndexOf(CharPool.SLASH, x);

			if (x == -1) {
				break;
			}

			String copyright = FileUtil.read(
				new File(absolutePath.substring(0, x + 1) + "copyright.txt"));

			if (Validator.isNotNull(copyright)) {
				return copyright;
			}

			x = x - 1;
		}

		return null;
	}

	protected List<String> getExcludes(String property) {
		List<String> excludes = _exclusionPropertiesMap.get(property);

		if (excludes != null) {
			return excludes;
		}

		excludes = getPropertyList(property);

		_exclusionPropertiesMap.put(property, excludes);

		return excludes;
	}

	protected File getFile(String fileName, int level) {
		return _sourceFormatterHelper.getFile(
			sourceFormatterArgs.getBaseDirName(), fileName, level);
	}

	protected abstract List<FileCheck> getFileChecks();

	protected List<String> getFileNames(
			String basedir, List<String> recentChangesFileNames,
			String[] excludes, String[] includes)
		throws Exception {

		return getFileNames(
			basedir, recentChangesFileNames, excludes, includes,
			sourceFormatterArgs.isIncludeSubrepositories());
	}

	protected List<String> getFileNames(
			String basedir, List<String> recentChangesFileNames,
			String[] excludes, String[] includes,
			boolean includeSubrepositories)
		throws Exception {

		if (_excludes != null) {
			excludes = ArrayUtil.append(excludes, _excludes);
		}

		return _sourceFormatterHelper.getFileNames(
			basedir, recentChangesFileNames, excludes, includes,
			includeSubrepositories);
	}

	protected List<String> getFileNames(
			String basedir, String[] excludes, String[] includes)
		throws Exception {

		return getFileNames(
			basedir, sourceFormatterArgs.getRecentChangesFileNames(), excludes,
			includes);
	}

	protected List<String> getFileNames(String[] excludes, String[] includes)
		throws Exception {

		return getFileNames(
			sourceFormatterArgs.getBaseDirName(), excludes, includes);
	}

	protected Set<String> getImmutableFieldTypes() {
		if (_immutableFieldTypes != null) {
			return _immutableFieldTypes;
		}

		Set<String> immutableFieldTypes = SetUtil.fromArray(
			new String[] {
				"boolean", "byte", "char", "double", "float", "int", "long",
				"short", "Boolean", "Byte", "Character", "Class", "Double",
				"Float", "Int", "Long", "Number", "Short", "String"
			});

		immutableFieldTypes.addAll(getPropertyList("immutable.field.types"));

		_immutableFieldTypes = immutableFieldTypes;

		return _immutableFieldTypes;
	}

	protected int getLeadingTabCount(String line) {
		int leadingTabCount = 0;

		while (line.startsWith(StringPool.TAB)) {
			line = line.substring(1);

			leadingTabCount++;
		}

		return leadingTabCount;
	}

	protected int getLevel(String s) {
		return getLevel(
			s, new String[] {StringPool.OPEN_PARENTHESIS},
			new String[] {StringPool.CLOSE_PARENTHESIS}, 0);
	}

	protected int getLevel(
		String s, String increaseLevelString, String decreaseLevelString) {

		return getLevel(
			s, new String[] {increaseLevelString},
			new String[] {decreaseLevelString}, 0);
	}

	protected int getLevel(
		String s, String[] increaseLevelStrings,
		String[] decreaseLevelStrings) {

		return getLevel(s, increaseLevelStrings, decreaseLevelStrings, 0);
	}

	protected int getLevel(
		String s, String[] increaseLevelStrings, String[] decreaseLevelStrings,
		int startLevel) {

		int level = startLevel;

		for (String increaseLevelString : increaseLevelStrings) {
			level = adjustLevel(level, s, increaseLevelString, 1);
		}

		for (String decreaseLevelString : decreaseLevelStrings) {
			level = adjustLevel(level, s, decreaseLevelString, -1);
		}

		return level;
	}

	protected String getLine(String content, int lineCount) {
		int nextLineStartPos = getLineStartPos(content, lineCount);

		if (nextLineStartPos == -1) {
			return null;
		}

		int nextLineEndPos = content.indexOf(
			CharPool.NEW_LINE, nextLineStartPos);

		if (nextLineEndPos == -1) {
			return content.substring(nextLineStartPos);
		}

		return content.substring(nextLineStartPos, nextLineEndPos);
	}

	protected int getLineCount(String content, int pos) {
		return StringUtil.count(content, 0, pos, CharPool.NEW_LINE) + 1;
	}

	protected int getLineLength(String line) {
		int lineLength = 0;

		int tabLength = 4;

		for (char c : line.toCharArray()) {
			if (c == CharPool.TAB) {
				for (int i = 0; i < tabLength; i++) {
					lineLength++;
				}

				tabLength = 4;
			}
			else {
				lineLength++;

				tabLength--;

				if (tabLength <= 0) {
					tabLength = 4;
				}
			}
		}

		return lineLength;
	}

	protected int getLineStartPos(String content, int lineCount) {
		int x = 0;

		for (int i = 1; i < lineCount; i++) {
			x = content.indexOf(CharPool.NEW_LINE, x + 1);

			if (x == -1) {
				return x;
			}
		}

		return x + 1;
	}

	protected ComparableVersion getMainReleaseComparableVersion(
			String fileName, String absolutePath, boolean checkModuleVersion)
		throws Exception {

		boolean usePortalReleaseVersion = true;

		if (checkModuleVersion &&
			(!portalSource || isModulesFile(absolutePath))) {

			usePortalReleaseVersion = false;
		}

		String releaseVersion = StringPool.BLANK;

		if (usePortalReleaseVersion) {
			if (_mainReleaseComparableVersion != null) {
				return _mainReleaseComparableVersion;
			}

			releaseVersion = ReleaseInfo.getVersion();
		}
		else {
			BNDSettings bndSettings = getBNDSettings(fileName);

			if (bndSettings == null) {
				return null;
			}

			releaseVersion = bndSettings.getReleaseVersion();

			if (releaseVersion == null) {
				return null;
			}

			putBNDSettings(bndSettings);
		}

		int pos = releaseVersion.lastIndexOf(CharPool.PERIOD);

		String mainReleaseVersion = releaseVersion.substring(0, pos) + ".0";

		ComparableVersion mainReleaseComparableVersion = new ComparableVersion(
			mainReleaseVersion);

		if (usePortalReleaseVersion) {
			_mainReleaseComparableVersion = mainReleaseComparableVersion;
		}

		return mainReleaseComparableVersion;
	}

	protected List<String> getParameterList(String methodCall) {
		String parameters = null;

		int x = -1;

		while (true) {
			x = methodCall.indexOf(StringPool.CLOSE_PARENTHESIS, x + 1);

			parameters = methodCall.substring(0, x + 1);

			if ((getLevel(parameters, "(", ")") == 0) &&
				(getLevel(parameters, "{", "}") == 0)) {

				break;
			}
		}

		x = parameters.indexOf(StringPool.OPEN_PARENTHESIS);

		parameters = parameters.substring(x + 1, parameters.length() - 1);

		return splitParameters(parameters);
	}

	protected List<String> getPluginsInsideModulesDirectoryNames()
		throws Exception {

		if (_pluginsInsideModulesDirectoryNames != null) {
			return _pluginsInsideModulesDirectoryNames;
		}

		List<String> pluginsInsideModulesDirectoryNames = new ArrayList<>();

		List<String> pluginBuildFileNames = getFileNames(
			new String[0],
			new String[] {
				"**/modules/apps/**/build.xml",
				"**/modules/private/apps/**/build.xml"
			});

		for (String pluginBuildFileName : pluginBuildFileNames) {
			pluginBuildFileName = StringUtil.replace(
				pluginBuildFileName, StringPool.BACK_SLASH, StringPool.SLASH);

			String absolutePath = getAbsolutePath(pluginBuildFileName);

			int x = absolutePath.indexOf("/modules/apps/");

			if (x == -1) {
				x = absolutePath.indexOf("/modules/private/apps/");
			}

			int y = absolutePath.lastIndexOf(StringPool.SLASH);

			pluginsInsideModulesDirectoryNames.add(
				absolutePath.substring(x, y + 1));
		}

		_pluginsInsideModulesDirectoryNames =
			pluginsInsideModulesDirectoryNames;

		return _pluginsInsideModulesDirectoryNames;
	}

	protected Properties getPortalLanguageProperties() throws Exception {
		Properties portalLanguageProperties = new Properties();

		File portalLanguagePropertiesFile = getFile(
			"portal-impl/src/content/Language.properties",
			PORTAL_MAX_DIR_LEVEL);

		if (portalLanguagePropertiesFile != null) {
			InputStream inputStream = new FileInputStream(
				portalLanguagePropertiesFile);

			portalLanguageProperties.load(inputStream);
		}

		return portalLanguageProperties;
	}

	protected String getProperty(String key) {
		return _properties.getProperty(key);
	}

	protected List<String> getPropertyList(String key) {
		return ListUtil.fromString(
			GetterUtil.getString(getProperty(key)), StringPool.COMMA);
	}

	protected boolean isAllowedVariableType(
		String content, String variableName,
		String[] variableTypeRegexStrings) {

		if (variableTypeRegexStrings.length == 0) {
			return true;
		}

		for (String variableTypeRegex : variableTypeRegexStrings) {
			StringBundler sb = new StringBundler(5);

			sb.append("[\\s\\S]*\\W");
			sb.append(variableTypeRegex);
			sb.append("\\s+");
			sb.append(variableName);
			sb.append("\\W[\\s\\S]*");

			if (content.matches(sb.toString())) {
				return true;
			}

			sb = new StringBundler(5);

			sb.append("[\\s\\S]*\\W");
			sb.append(variableName);
			sb.append(" =\\s+new ");
			sb.append(variableTypeRegex);
			sb.append("[\\s\\S]*");

			if (content.matches(sb.toString())) {
				return true;
			}
		}

		return false;
	}

	protected boolean isExcludedPath(String property, String path) {
		return isExcludedPath(property, path, -1);
	}

	protected boolean isExcludedPath(
		String property, String path, int lineCount) {

		return isExcludedPath(property, path, lineCount, null);
	}

	protected boolean isExcludedPath(
		String property, String path, int lineCount, String parameter) {

		if (property == null) {
			return false;
		}

		List<String> excludes = _exclusionPropertiesMap.get(property);

		if (excludes == null) {
			excludes = getPropertyList(property);

			_exclusionPropertiesMap.put(property, excludes);
		}

		if (ListUtil.isEmpty(excludes)) {
			return false;
		}

		String pathWithParameter = null;

		if (Validator.isNotNull(parameter)) {
			pathWithParameter = path + StringPool.AT + parameter;
		}

		String pathWithLineCount = null;

		if (lineCount > 0) {
			pathWithLineCount = path + StringPool.AT + lineCount;
		}

		for (String exclude : excludes) {
			if (Validator.isNull(exclude)) {
				continue;
			}

			if (exclude.startsWith("**")) {
				exclude = exclude.substring(2);
			}

			if (exclude.endsWith("**")) {
				exclude = exclude.substring(0, exclude.length() - 2);

				if (path.contains(exclude)) {
					return true;
				}

				continue;
			}

			if (path.endsWith(exclude) ||
				((pathWithParameter != null) &&
				 pathWithParameter.endsWith(exclude)) ||
				((pathWithLineCount != null) &&
				 pathWithLineCount.endsWith(exclude))) {

				return true;
			}
		}

		return false;
	}

	protected boolean isExcludedPath(
		String property, String path, String parameter) {

		return isExcludedPath(property, path, -1, parameter);
	}

	protected boolean isModulesApp(String absolutePath, boolean privateOnly) {
		if (absolutePath.contains("/modules/private/apps/") ||
			(!privateOnly && absolutePath.contains("/modules/apps/"))) {

			return true;
		}

		if (_projectPathPrefix == null) {
			return false;
		}

		if (_projectPathPrefix.startsWith(":private:apps") ||
			(!privateOnly && _projectPathPrefix.startsWith(":apps:"))) {

			return true;
		}

		return false;
	}

	protected boolean isModulesFile(String absolutePath) {
		return isModulesFile(absolutePath, false);
	}

	protected boolean isModulesFile(
		String absolutePath, boolean includePlugins) {

		if (subrepository) {
			return true;
		}

		if (includePlugins) {
			return absolutePath.contains("/modules/");
		}

		try {
			for (String directoryName :
					getPluginsInsideModulesDirectoryNames()) {

				if (absolutePath.contains(directoryName)) {
					return false;
				}
			}
		}
		catch (Exception e) {
		}

		return absolutePath.contains("/modules/");
	}

	protected abstract void populateFileChecks() throws Exception ;

	protected void postFormat() throws Exception {
	}

	protected void preFormat() throws Exception {
	}

	protected void printError(String fileName, String message) {
		if (sourceFormatterArgs.isPrintErrors()) {
			_sourceFormatterHelper.printError(fileName, message);
		}
	}

	protected String processFileChecks(
			String fileName, String absolutePath, String content)
		throws Exception {

		List<FileCheck> fileChecks = getFileChecks();

		if (fileChecks == null) {
			return content;
		}

		for (FileCheck fileCheck : fileChecks) {
			Tuple tuple = fileCheck.process(fileName, absolutePath, content);

			content = (String)tuple.getObject(0);

			Set<SourceFormatterMessage> sourceFormatterMessages =
				(Set<SourceFormatterMessage>)tuple.getObject(1);

			for (SourceFormatterMessage sourceFormatterMessage :
					sourceFormatterMessages) {

				processMessage(fileName, sourceFormatterMessage);
			}
		}

		return content;
	}

	protected void processFormattedFile(
			File file, String fileName, String content, String newContent)
		throws Exception {

		if (!content.equals(newContent)) {
			if (sourceFormatterArgs.isPrintErrors()) {
				_sourceFormatterHelper.printError(fileName, file);
			}

			if (sourceFormatterArgs.isAutoFix()) {
				FileUtil.write(file, newContent);
			}
			else if (_firstSourceMismatchException == null) {
				_firstSourceMismatchException = new SourceMismatchException(
					fileName, content, newContent);
			}
		}

		if (sourceFormatterArgs.isPrintErrors()) {
			Set<SourceFormatterMessage> sourceFormatterMessages =
				_sourceFormatterMessagesMap.get(fileName);

			if (sourceFormatterMessages != null) {
				for (SourceFormatterMessage sourceFormatterMessage :
						sourceFormatterMessages) {

					_sourceFormatterHelper.printError(
						fileName, sourceFormatterMessage.toString());

					if (_browserStarted ||
						!sourceFormatterArgs.isShowDocumentation() ||
						!Desktop.isDesktopSupported()) {

						continue;
					}

					String markdownFileName =
						sourceFormatterMessage.getMarkdownFileName();

					if (Validator.isNotNull(markdownFileName)) {
						Desktop desktop = Desktop.getDesktop();

						desktop.browse(
							new URI(_DOCUMENTATION_URL + markdownFileName));

						_browserStarted = true;
					}
				}
			}
		}

		_modifiedFileNames.add(file.getAbsolutePath());
	}

	protected void processMessage(
		String fileName, SourceFormatterMessage sourceFormatterMessage) {

		Set<SourceFormatterMessage> sourceFormatterMessages =
			_sourceFormatterMessagesMap.get(fileName);

		if (sourceFormatterMessages == null) {
			sourceFormatterMessages = new TreeSet<>();
		}

		sourceFormatterMessages.add(sourceFormatterMessage);

		_sourceFormatterMessagesMap.put(fileName, sourceFormatterMessages);
	}

	protected void putBNDSettings(BNDSettings bndSettings) {
		_bndSettingsMap.put(bndSettings.getFileLocation(), bndSettings);
	}

	protected Document readXML(String content) throws DocumentException {
		SAXReader saxReader = SAXReaderFactory.getSAXReader(null, false, false);

		return saxReader.read(new UnsyncStringReader(content));
	}

	protected String replacePrimitiveWrapperInstantiation(String line) {
		return StringUtil.replace(
			line,
			new String[] {
				"new Boolean(", "new Byte(", "new Character(", "new Double(",
				"new Float(", "new Integer(", "new Long(", "new Short("
			},
			new String[] {
				"Boolean.valueOf(", "Byte.valueOf(", "Character.valueOf(",
				"Double.valueOf(", "Float.valueOf(", "Integer.valueOf(",
				"Long.valueOf(", "Short.valueOf("
			});
	}

	protected String sortDefinitions(
		String fileName, String content, Comparator<String> comparator) {

		String previousDefinition = null;

		Matcher matcher = _definitionPattern.matcher(content);

		while (matcher.find()) {
			String newContent = formatDefinitionKey(
				fileName, content, matcher.group(1));

			if (!newContent.equals(content)) {
				return newContent;
			}

			String definition = matcher.group();

			if (Validator.isNotNull(matcher.group(1)) &&
				definition.endsWith("\n")) {

				definition = definition.substring(0, definition.length() - 1);
			}

			if (Validator.isNotNull(previousDefinition)) {
				int value = comparator.compare(previousDefinition, definition);

				if (value > 0) {
					content = StringUtil.replaceFirst(
						content, previousDefinition, definition);
					content = StringUtil.replaceLast(
						content, definition, previousDefinition);

					return content;
				}

				if (value == 0) {
					return StringUtil.replaceFirst(
						content, previousDefinition + "\n", StringPool.BLANK);
				}
			}

			previousDefinition = definition;
		}

		return content;
	}

	protected String sortMethodCall(
		String content, String methodName, String... variableTypeRegexStrings) {

		Pattern codeBlockPattern = Pattern.compile(
			"(\t+(\\w*)\\." + methodName + "\\(\\s*\".*?\\);\n)+",
			Pattern.DOTALL);

		Matcher codeBlockMatcher = codeBlockPattern.matcher(content);

		PutOrSetParameterNameComparator putOrSetParameterNameComparator =
			new PutOrSetParameterNameComparator();

		while (codeBlockMatcher.find()) {
			if (!isAllowedVariableType(
					content, codeBlockMatcher.group(2),
					variableTypeRegexStrings)) {

				continue;
			}

			String codeBlock = codeBlockMatcher.group();

			Pattern singleLineMethodCallPattern = Pattern.compile(
				"\t*\\w*\\." + methodName + "\\((.*?)\\);\n", Pattern.DOTALL);

			Matcher singleLineMatcher = singleLineMethodCallPattern.matcher(
				codeBlock);

			String previousParameters = null;
			String previousPutOrSetParameterName = null;

			while (singleLineMatcher.find()) {
				String parameters = singleLineMatcher.group(1);

				List<String> parametersList = splitParameters(parameters);

				String putOrSetParameterName = parametersList.get(0);

				if ((previousPutOrSetParameterName != null) &&
					(putOrSetParameterNameComparator.compare(
						previousPutOrSetParameterName, putOrSetParameterName) >
							0)) {

					String newCodeBlock = StringUtil.replaceFirst(
						codeBlock, previousParameters, parameters);
					newCodeBlock = StringUtil.replaceLast(
						newCodeBlock, parameters, previousParameters);

					return StringUtil.replace(content, codeBlock, newCodeBlock);
				}

				previousParameters = parameters;
				previousPutOrSetParameterName = putOrSetParameterName;
			}
		}

		return content;
	}

	protected String sortMethodCalls(String absolutePath, String content) {
		if (isExcludedPath(METHOD_CALL_SORT_EXCLUDES, absolutePath)) {
			return content;
		}

		content = sortMethodCall(
			content, "add", "ConcurrentSkipListSet<.*>", "HashSet<.*>",
			"TreeSet<.*>");
		content = sortMethodCall(
			content, "put", "ConcurrentHashMap<.*>", "HashMap<.*>",
			"JSONObject", "TreeMap<.*>");
		content = sortMethodCall(content, "setAttribute");

		return content;
	}

	protected List<String> splitParameters(String parameters) {
		List<String> parametersList = new ArrayList<>();

		int x = -1;

		while (true) {
			x = parameters.indexOf(StringPool.COMMA, x + 1);

			if (x == -1) {
				parametersList.add(StringUtil.trim(parameters));

				return parametersList;
			}

			if (ToolsUtil.isInsideQuotes(parameters, x)) {
				continue;
			}

			String linePart = parameters.substring(0, x);

			if ((getLevel(linePart, "(", ")") == 0) &&
				(getLevel(linePart, "{", "}") == 0)) {

				parametersList.add(StringUtil.trim(linePart));

				parameters = parameters.substring(x + 1);

				x = -1;
			}
		}
	}

	protected String stripQuotes(String s) {
		return stripQuotes(s, CharPool.APOSTROPHE, CharPool.QUOTE);
	}

	protected String stripQuotes(String s, char... delimeters) {
		List<Character> delimetersList = ListUtil.toList(delimeters);

		char delimeter = CharPool.SPACE;
		boolean insideQuotes = false;

		StringBundler sb = new StringBundler();

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			if (insideQuotes) {
				if (c == delimeter) {
					int precedingBackSlashCount = 0;

					for (int j = (i - 1); j >= 0; j--) {
						if (s.charAt(j) == CharPool.BACK_SLASH) {
							precedingBackSlashCount += 1;
						}
						else {
							break;
						}
					}

					if ((precedingBackSlashCount == 0) ||
						((precedingBackSlashCount % 2) == 0)) {

						insideQuotes = false;
					}
				}
			}
			else if (delimetersList.contains(c)) {
				delimeter = c;
				insideQuotes = true;
			}
			else {
				sb.append(c);
			}
		}

		return sb.toString();
	}

	protected static final String LANGUAGE_KEYS_CHECK_EXCLUDES =
		"language.keys.check.excludes";

	protected static final String METHOD_CALL_SORT_EXCLUDES =
		"method.call.sort.excludes";

	protected static final String RUN_OUTSIDE_PORTAL_EXCLUDES =
		"run.outside.portal.excludes";

	protected static Pattern emptyArrayPattern = Pattern.compile(
		"((\\[\\])+) \\{\\}");
	protected static Pattern emptyCollectionPattern = Pattern.compile(
		"Collections\\.EMPTY_(LIST|MAP|SET)");
	protected static Pattern getterUtilGetPattern = Pattern.compile(
		"GetterUtil\\.get(Boolean|Double|Float|Integer|Number|Object|Short|" +
			"String)\\((.*?)\\);\n",
		Pattern.DOTALL);
	protected static Pattern javaSourceInsideJSPLinePattern = Pattern.compile(
		"<%=(.+?)%>");
	protected static boolean portalSource;
	protected static Pattern principalExceptionPattern = Pattern.compile(
		"SessionErrors\\.contains\\(\n?\t*(renderR|r)equest, " +
			"PrincipalException\\.class\\.getName\\(\\)");
	protected static Pattern sbAppendPattern = Pattern.compile(
		"\\s*\\w*(sb|SB)[0-9]?\\.append\\(\\s*(\\S.*?)\\);\n", Pattern.DOTALL);
	protected static Pattern sbAppendWithStartingSpacePattern = Pattern.compile(
		"\n(\t*\\w*(sb|SB)[0-9]?\\.append\\(\".*\"\\);)\n\\s*\\w*(sb|SB)" +
			"[0-9]?\\.append\\(\" .*\"\\);\n");
	protected static Pattern sessionKeyPattern = Pattern.compile(
		"SessionErrors.(?:add|contains|get)\\([^;%&|!]+|".concat(
			"SessionMessages.(?:add|contains|get)\\([^;%&|!]+"),
		Pattern.MULTILINE);
	protected static Pattern singleLengthStringPattern = Pattern.compile(
		"^(\".\"|StringPool\\.([A-Z_]+))$");
	protected static Pattern stringUtilReplacePattern = Pattern.compile(
		"StringUtil\\.(replace(First|Last)?)\\((.*?)\\);\n", Pattern.DOTALL);
	protected static boolean subrepository;
	protected static Pattern taglibSessionKeyPattern = Pattern.compile(
		"<liferay-ui:error [^>]+>|<liferay-ui:success [^>]+>",
		Pattern.MULTILINE);
	protected static Pattern unparameterizedClassTypePattern1 =
		Pattern.compile("\\Wnew Class[^<\\w]");
	protected static Pattern unparameterizedClassTypePattern2 =
		Pattern.compile("\\WClass[\\[\\]]* \\w+ =");
	protected static Pattern validatorEqualsPattern = Pattern.compile(
		"\\WValidator\\.equals\\(");

	protected SourceFormatterArgs sourceFormatterArgs;

	private String[] _getExcludes() {
		if (sourceFormatterArgs.getFileNames() != null) {
			return new String[0];
		}

		List<String> excludesList = ListUtil.fromString(
			GetterUtil.getString(
				System.getProperty("source.formatter.excludes")));

		excludesList.addAll(getPropertyList("source.formatter.excludes"));

		return excludesList.toArray(new String[excludesList.size()]);
	}

	private String _getProjectPathPrefix() throws Exception {
		if (!subrepository) {
			return null;
		}

		File file = getFile("gradle.properties", PORTAL_MAX_DIR_LEVEL);

		if (!file.exists()) {
			return null;
		}

		Properties properties = new Properties();

		properties.load(new FileInputStream(file));

		return properties.getProperty("project.path.prefix");
	}

	private void _init() {
		try {
			_sourceFormatterHelper = new SourceFormatterHelper(
				sourceFormatterArgs.isUseProperties());

			_sourceFormatterHelper.init();

			portalSource = _isPortalSource();
			subrepository = _isSubrepository();

			_projectPathPrefix = _getProjectPathPrefix();

			_sourceFormatterMessagesMap = new HashMap<>();
		}
		catch (Exception e) {
			ReflectionUtil.throwException(e);
		}

		_excludes = _getExcludes();

		_usePortalCompatImport = GetterUtil.getBoolean(
			getProperty("use.portal.compat.import"));
	}

	private boolean _isMatchPath(String fileName) {
		for (String pattern : getIncludes()) {
			if (SelectorUtils.matchPath(_normalizePattern(pattern), fileName)) {
				return true;
			}
		}

		return false;
	}

	private boolean _isPortalSource() {
		if (getFile("portal-impl", PORTAL_MAX_DIR_LEVEL) != null) {
			return true;
		}

		return false;
	}

	private boolean _isSubrepository() {
		String baseDirAbsolutePath = getAbsolutePath(
			sourceFormatterArgs.getBaseDirName());

		int x = baseDirAbsolutePath.length();

		for (int i = 0; i < 2; i++) {
			x = baseDirAbsolutePath.lastIndexOf(CharPool.FORWARD_SLASH, x - 1);

			if (x == -1) {
				return false;
			}

			String dirName = baseDirAbsolutePath.substring(x + 1);

			if (dirName.startsWith("com-liferay-")) {
				return true;
			}
		}

		return false;
	}

	private String _normalizePattern(String originalPattern) {
		String pattern = originalPattern.replace(
			CharPool.SLASH, File.separatorChar);

		pattern = pattern.replace(CharPool.BACK_SLASH, File.separatorChar);

		if (pattern.endsWith(File.separator)) {
			pattern += SelectorUtils.DEEP_TREE_MATCH;
		}

		return pattern;
	}

	private static final String _DOCUMENTATION_URL =
		"https://github.com/liferay/liferay-portal/blob/master/modules/util" +
			"/source-formatter/documentation/";

	private Set<String> _annotationsExclusions;
	private Map<String, BNDSettings> _bndSettingsMap =
		new ConcurrentHashMap<>();
	private boolean _browserStarted;
	private Map<String, String> _compatClassNamesMap;
	private String _copyright;
	private final Pattern _definitionPattern = Pattern.compile(
		"^([A-Za-z-]+?)[:=](\n|[\\s\\S]*?([^\\\\]\n|\\Z))", Pattern.MULTILINE);
	private String[] _excludes;
	private Map<String, List<String>> _exclusionPropertiesMap = new HashMap<>();
	private SourceMismatchException _firstSourceMismatchException;
	private Set<String> _immutableFieldTypes;
	private ComparableVersion _mainReleaseComparableVersion;
	private final List<String> _modifiedFileNames =
		new CopyOnWriteArrayList<>();
	private String _oldCopyright;
	private List<String> _pluginsInsideModulesDirectoryNames;
	private String _projectPathPrefix;
	private Properties _properties;
	private SourceFormatterHelper _sourceFormatterHelper;
	private Map<String, Set<SourceFormatterMessage>>
		_sourceFormatterMessagesMap = new ConcurrentHashMap<>();
	private boolean _usePortalCompatImport;

	private class PutOrSetParameterNameComparator
		extends NaturalOrderStringComparator {

		@Override
		public int compare(
			String putOrSetParameterName1, String putOrSetParameterName2) {

			String strippedParameterName1 = stripQuotes(putOrSetParameterName1);
			String strippedParameterName2 = stripQuotes(putOrSetParameterName2);

			if (strippedParameterName1.contains(StringPool.OPEN_PARENTHESIS) ||
				strippedParameterName2.contains(StringPool.OPEN_PARENTHESIS)) {

				return 0;
			}

			Matcher matcher = _multipleLineParameterNamePattern.matcher(
				putOrSetParameterName1);

			if (matcher.find()) {
				putOrSetParameterName1 = matcher.replaceAll(StringPool.BLANK);
			}

			matcher = _multipleLineParameterNamePattern.matcher(
				putOrSetParameterName2);

			if (matcher.find()) {
				putOrSetParameterName2 = matcher.replaceAll(StringPool.BLANK);
			}

			if (putOrSetParameterName1.matches("\".*\"") &&
				putOrSetParameterName2.matches("\".*\"")) {

				String strippedQuotes1 = putOrSetParameterName1.substring(
					1, putOrSetParameterName1.length() - 1);
				String strippedQuotes2 = putOrSetParameterName2.substring(
					1, putOrSetParameterName2.length() - 1);

				return super.compare(strippedQuotes1, strippedQuotes2);
			}

			int value = super.compare(
				putOrSetParameterName1, putOrSetParameterName2);

			if (putOrSetParameterName1.startsWith(StringPool.QUOTE) ^
				putOrSetParameterName2.startsWith(StringPool.QUOTE)) {

				return -value;
			}

			return value;
		}

		private final Pattern _multipleLineParameterNamePattern =
			Pattern.compile("\" \\+\n\t+\"");

	}

}