module.exports = {
  dependency: {
    platforms: {
      android: {
        packageImportPath: 'import com.livetracking.LiveTrackingPackage;',
        packageInstance: 'new LiveTrackingPackage()',
        cmakeListsPath: null,
      },
      ios: {},
    },
  },
};
