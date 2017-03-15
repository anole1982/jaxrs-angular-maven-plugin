
package com.primus;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.primus.parser.SourceType;
import com.primus.util.Predicate;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class Input
{

	private final List<SourceType<Type>> sourceTypes;

	private Input(List<SourceType<Type>> sourceTypes)
	{
		this.sourceTypes = sourceTypes;
	}

	public List<SourceType<Type>> getSourceTypes()
	{
		return sourceTypes;
	}

	public static Input from(Type... types)
	{
		final List<SourceType<Type>> sourceTypes = new ArrayList<>();
		for (Type type : types)
		{
			sourceTypes.add(new SourceType<>(type));
		}
		return new Input(sourceTypes);
	}

	public static Input fromClassNamesAndJaxrsApplication(List<String> classNames, List<String> classNamePatterns, String jaxrsApplicationClassName,
			boolean automaticJaxrsApplication, Predicate<String> isClassNameExcluded, ClassLoader classLoader)
	{
		final ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
		try
		{
			Thread.currentThread().setContextClassLoader(classLoader);
			final ClasspathScanner classpathScanner = new ClasspathScanner();
			final List<SourceType<Type>> types = new ArrayList<>();
			if (classNames != null)
			{
				types.addAll(fromClassNames(classNames).getSourceTypes());
			}
			if (classNamePatterns != null)
			{
				types.addAll(fromClassNamePatterns(classpathScanner.scanClasspath(), classNamePatterns).getSourceTypes());
			}
			if (jaxrsApplicationClassName != null)
			{
				types.addAll(fromClassNames(Arrays.asList(jaxrsApplicationClassName)).sourceTypes);
			}
			if (automaticJaxrsApplication)
			{
				types.addAll(JaxrsApplicationScanner.scanAutomaticJaxrsApplication(classpathScanner.scanClasspath(), isClassNameExcluded));
			}
			if (types.isEmpty())
			{
				final String errorMessage = "No input classes found.";
				System.out.println(errorMessage);
				throw new RuntimeException(errorMessage);
			}
			return new Input(types);
		}
		finally
		{
			Thread.currentThread().setContextClassLoader(originalContextClassLoader);
		}
	}

	private static class ClasspathScanner
	{

		private ScanResult scanResult = null;

		public ScanResult scanClasspath()
		{
			if (scanResult == null)
			{
				System.out.println("Scanning classpath");
				final Date scanStart = new Date();
				final ScanResult scanner = new FastClasspathScanner().scan();
				final int count = scanner.getNamesOfAllClasses().size();
				final Date scanEnd = new Date();
				final double timeInSeconds = (scanEnd.getTime() - scanStart.getTime()) / 1000.0;
				System.out.println(String.format("Scanning finished in %.2f seconds. Total number of classes: %d.", timeInSeconds, count));
				scanResult = scanner;
			}
			return scanResult;
		}

	}

	private static Input fromClassNamePatterns(ScanResult scanner, List<String> classNamePatterns)
	{
		final List<String> allClassNames = new ArrayList<>();
		allClassNames.addAll(scanner.getNamesOfAllStandardClasses());
		allClassNames.addAll(scanner.getNamesOfAllInterfaceClasses());
		Collections.sort(allClassNames);
		final List<String> classNames = filterClassNames(allClassNames, classNamePatterns);
		System.out.println(String.format("Found %d classes matching pattern.", classNames.size()));
		return fromClassNames(classNames);
	}

	private static Input fromClassNames(List<String> classNames)
	{
		try
		{
			final List<SourceType<Type>> types = new ArrayList<>();
			for (String className : classNames)
			{
				final Class<?> cls = Thread.currentThread().getContextClassLoader().loadClass(className);
				// skip synthetic classes (as those generated by java compiler for switch with enum)
				// and anonymous classes (should not be processed and they do not have SimpleName)
				if (!cls.isSynthetic() && !cls.isAnonymousClass())
				{
					types.add(new SourceType<Type>(cls, null, null));
				}
			}
			return new Input(types);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException(e);
		}
	}

	static List<String> filterClassNames(List<String> classNames, List<String> globs)
	{
		final List<Pattern> regexps = globsToRegexps(globs);
		final List<String> result = new ArrayList<>();
		for (String className : classNames)
		{
			if (classNameMatches(className, regexps))
			{
				result.add(className);
			}
		}
		return result;
	}

	static boolean classNameMatches(String className, List<Pattern> regexps)
	{
		for (Pattern regexp : regexps)
		{
			if (regexp.matcher(className).matches())
			{
				return true;
			}
		}
		return false;
	}

	static List<Pattern> globsToRegexps(List<String> globs)
	{
		final List<Pattern> regexps = new ArrayList<>();
		for (String glob : globs)
		{
			regexps.add(globToRegexp(glob));
		}
		return regexps;
	}

	/**
	 * Creates regexp for glob pattern. Replaces "*" with "[^.\$]*" and "**" with ".*".
	 */
	static Pattern globToRegexp(String glob)
	{
		final Pattern globToRegexpPattern = Pattern.compile("(\\*\\*)|(\\*)");
		final Matcher matcher = globToRegexpPattern.matcher(glob);
		final StringBuffer sb = new StringBuffer();
		int lastEnd = 0;
		while (matcher.find())
		{
			sb.append(Pattern.quote(glob.substring(lastEnd, matcher.start())));
			if (matcher.group(1) != null)
			{
				sb.append(Matcher.quoteReplacement(".*"));
			}
			if (matcher.group(2) != null)
			{
				sb.append(Matcher.quoteReplacement("[^.$]*"));
			}
			lastEnd = matcher.end();
		}
		sb.append(Pattern.quote(glob.substring(lastEnd, glob.length())));
		return Pattern.compile(sb.toString());
	}

}
