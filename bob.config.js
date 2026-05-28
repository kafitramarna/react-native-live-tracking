/**
 * react-native-builder-bob configuration.
 * This can also be specified in package.json under "react-native-builder-bob" key.
 * See: https://github.com/callstack/react-native-builder-bob
 */
module.exports = {
  source: 'src',
  output: 'lib',
  targets: [
    'commonjs',
    'module',
    ['typescript', { project: 'tsconfig.build.json' }],
  ],
};
