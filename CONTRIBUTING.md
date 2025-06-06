# Contributing to UUIDv7

First off, **thank you** for considering a contribution to UUIDv7! Your support and feedback make this library better for everyone.

## Ways to Contribute

1. **Bug Reports & Feature Requests**
    - Open a new [Issue](https://github.com/robsonkades/uuidv7/issues) describing the bug or feature.
    - Provide as much detail as possible: steps to reproduce, JVM version, operating system, stack traces, etc.

2. **Pull Requests**
    - Fork the repository and create a new branch:
      ```bash
      git checkout -b feature/your-feature-name
      ```
    - Write clear, concise commit messages.
    - Follow existing code style (bitwise operations, indentation, etc.).
    - Include **unit tests** for any new functionality or bug fixes. Use JUnit 5 or JUnit 4 tests in `src/test/java`.
    - Run `mvn clean verify` locally to ensure compilation and Javadoc generation succeed.
    - Submit a **Pull Request** targeting the `main` branch. Provide a thorough description of your changes.

3. **Documentation Improvements**
    - Found a typo in the docs? Think of an example that would better illustrate how to use `UUIDv7`?
    - Submit a PR updating `README.md`, Javadocs, or this `CONTRIBUTING.md`.

4. **Benchmarks & Performance Testing**
    - If you have additional ideas for benchmarking or comparative tests (e.g., GPU-accelerated environments), please share code in a new folder `benchmark/`.
    - Update `README.md` or create a new `BENCHMARK.md` with detailed bench instructions.

## Coding Style

- Target Java 8+ (project currently compiles with Java 8).
- Document any public method with Javadoc, including `{@link ...}` tags and code examples when appropriate.
- If you modify `pom.xml`, ensure file structure and indentation remain consistent.

## Pull Request Checklist

- [ ] Your code compiles and tests pass (`mvn clean test`).
- [ ] New or updated methods include proper Javadoc (`mvn javadoc:javadoc` should produce no errors).
- [ ] Benchmarks, if added, are placed under a `benchmark/` folder with instructions in `README.md` or `BENCHMARK.md`.
- [ ] You have added yourself as a contributor in the `README.md` or `AUTHORS.md` (if relevant).
- [ ] The PR description explains both “what” and “why” (not just “how”) you made the change.

Once your PR is approved, one of the maintainers will merge it and trigger CI to run additional checks. Thank you for making UUIDv7 better!

---

## Reporting Security Issues

If you discover a security vulnerability or crash that could be exploited, please do **not** open a public issue. Instead, send an email to `robsonkades@outlook.com` with details and steps to reproduce. We take security seriously and will respond promptly.

