/*
 * Copyright 2015 the original author or authors.
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

/* global glowroot, $ */

glowroot.controller('TransactionMetricsCtrl', [
  '$scope',
  '$location',
  '$http',
  'charts',
  function ($scope, $location, $http, charts) {

    $scope.$parent.activeTabItem = 'metrics';

    var chartState = charts.createState();

    $scope.$watchGroup(['chartFrom', 'chartTo', 'chartRefresh'], function (newValues, oldValues) {
      if (newValues !== oldValues) {
        charts.refreshData('backend/transaction/metrics', chartState, $scope, onRefreshData);
      }
    });

    function onRefreshData(data) {
      $scope.transactionCounts = data.transactionCounts;
      $scope.mergedAggregate = data.mergedAggregate;
      $scope.threadInfoAggregate = data.threadInfoAggregate;
      if ($scope.mergedAggregate.transactionCount) {
        updateTreeTimers();
        updateFlattenedTimers();
      }
    }

    function updateTreeTimers() {
      var treeTimers = [];

      function traverse(timer, nestingLevel) {
        timer.nestingLevel = nestingLevel;
        treeTimers.push(timer);
        if (timer.nestedTimers) {
          timer.nestedTimers.sort(function (a, b) {
            return b.totalMicros - a.totalMicros;
          });
          $.each(timer.nestedTimers, function (index, nestedTimer) {
            traverse(nestedTimer, nestingLevel + 1);
          });
        }
      }

      traverse($scope.mergedAggregate.timers, 0);

      $scope.treeTimers = treeTimers;
    }

    function updateFlattenedTimers() {
      var flattenedTimerMap = {};
      var flattenedTimers = [];

      function traverse(timer, parentTimerNames) {
        var flattenedTimer = flattenedTimerMap[timer.name];
        if (!flattenedTimer) {
          flattenedTimer = {
            name: timer.name,
            totalMicros: timer.totalMicros,
            count: timer.count
          };
          flattenedTimerMap[timer.name] = flattenedTimer;
          flattenedTimers.push(flattenedTimer);
        } else if (parentTimerNames.indexOf(timer.name) === -1) {
          // only add to existing flattened timer if the aggregate timer isn't appearing under itself
          // (this is possible when they are separated by another aggregate timer)
          flattenedTimer.totalMicros += timer.totalMicros;
          flattenedTimer.count += timer.count;
        }
        if (timer.nestedTimers) {
          $.each(timer.nestedTimers, function (index, nestedTimer) {
            traverse(nestedTimer, parentTimerNames.concat(timer));
          });
        }
      }

      traverse($scope.mergedAggregate.timers, []);

      flattenedTimers.sort(function (a, b) {
        return b.totalMicros - a.totalMicros;
      });

      $scope.flattenedTimers = flattenedTimers;
    }

    var chartOptions = {
      tooltip: true,
      tooltipOpts: {
        content: function (label, xval, yval, flotItem) {
          var total = 0;
          var seriesIndex;
          var dataSeries;
          var value;
          var plotData = chartState.plot.getData();
          for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
            dataSeries = plotData[seriesIndex];
            value = dataSeries.data[flotItem.dataIndex][1];
            total += value;
          }
          if (total === 0) {
            return 'No data';
          }
          var from = xval - chartState.dataPointIntervalMillis;
          // this math is to deal with active aggregate
          from = Math.ceil(from / chartState.dataPointIntervalMillis) * chartState.dataPointIntervalMillis;
          var to = xval;
          return charts.renderTooltipHtml(from, to, $scope.transactionCounts[xval], flotItem.dataIndex,
              flotItem.seriesIndex, chartState.plot, function (value) {
                return (100 * value / total).toFixed(1) + ' %';
              });
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    charts.refreshData('backend/transaction/metrics', chartState, $scope, onRefreshData);
  }
]);
