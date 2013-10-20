/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global informant */

informant.controller('JvmMemoryOverviewCtrl', [
  '$scope',
  '$http',
  '$q',
  'httpErrors',
  function ($scope, $http, $q, httpErrors) {

    $scope.performGC = function (deferred) {
      $http.post('backend/jvm/perform-gc')
          .success(function (data) {
            $scope.loaded = true;
            $scope.data = data;
            deferred.resolve('Complete');
          })
          .error(function (data, status) {
            if (status === 0) {
              deferred.reject('Unable to connect to server');
            } else {
              deferred.reject('An error occurred');
            }
          });
    };

    $scope.resetPeakUsage = function (deferred) {
      $http.post('backend/jvm/reset-peak-memory-usage')
          .success(function (data) {
            $scope.loaded = true;
            $scope.data = data;
            deferred.resolve('Complete');
          })
          .error(function (data, status) {
            if (status === 0) {
              deferred.reject('Unable to connect to server');
            } else {
              deferred.reject('An error occurred');
            }
          });
    };

    $scope.refresh = function (deferred) {
      $http.get('backend/jvm/memory-overview')
          .success(function (data) {
            $scope.loaded = true;
            $scope.data = data;
            deferred.resolve('Refreshed');
          })
          .error(function (data, status) {
            deferred.reject(httpErrors.get(data, status));
          });
    };

    var deferred = $q.defer();
    deferred.promise.then(function () {
      $scope.loaded = true;
    }, function (rejection) {
      $scope.loadingError = rejection;
    });
    $scope.refresh(deferred);
  }
]);
