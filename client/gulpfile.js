
'use strict';

var gulp        = require('gulp');
var $           = require('gulp-load-plugins')();
var browserify  = require('browserify');
var watchify    = require('watchify');
var reactify    = require('reactify');
var source      = require('vinyl-source-stream');
var shim        = require('browserify-shim');
var runSequence = require('run-sequence');

var buildDir   = '../public/';

function handleError(task) {
  return function(err) {
    $.util.log($.util.colors.white(err));
    $.notify.onError(task + " failed")(err);
  };
}

// see https://gist.github.com/mitchelkuijpers/11281981
function jsx(watch) {
  var doify = watch ? watchify : browserify;
  var bundler = doify('./scripts/setup.js', { extensions: ['.jsx'] });

  bundler.transform(reactify);
  bundler.transform(shim);

  var rebundle = function() {
    var stream = bundler.bundle({debug: true});

    stream.on('error', handleError('browserify'));

    return stream
      .pipe(source('setup.js'))
      .pipe(gulp.dest(buildDir + 'javascripts'));
  };

  bundler.on('update', rebundle);
  return rebundle();
}

gulp.task('jsx', function() { return jsx(false); });
gulp.task('jsx:watch', function() { return jsx(true); });


gulp.task('elm', function() {
  return gulp.src('src/Main.elm')
    .pipe($.elm())
    .on('error', handleError("elm")) //function(error) { console.log(error.message); })
    .pipe(gulp.dest(buildDir + 'javascripts'));
});


gulp.task('compress', function() {
  gulp.src(buildDir + 'javascripts/setup.js')
    .pipe($.uglify())
    .pipe(gulp.dest(buildDir + 'javascripts/dist'));
});

// Compile Any Other Sass Files You Added (app/styles)
gulp.task('scss', function () {
  return gulp.src('styles/**/*.scss')
    .pipe($.sass({
      style: 'expanded',
      precision: 10,
      loadPath: ['styles']
    }))
    .on('error', handleError("scss"))
    .pipe(gulp.dest(buildDir + 'stylesheets'))
    .pipe($.size({title: 'scss'}));
});

// Watch Files For Changes & Reload
gulp.task('default', ['jsx:watch', 'scss', 'elm'], function () {
  gulp.watch(['styles/**/*.scss'], ['scss']);
  gulp.watch(['src/**/*.elm'], ['elm']);
});

// Build Production Files, the Default Task
gulp.task('dist', function (cb) {
  runSequence('scss', ['jsx', 'compress'], cb);
});

