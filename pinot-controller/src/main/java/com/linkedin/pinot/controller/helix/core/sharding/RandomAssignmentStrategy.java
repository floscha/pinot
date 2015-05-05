/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.controller.helix.core.sharding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.helix.HelixAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.common.utils.BrokerRequestUtils;


/**
 * Random assign segment to instances.
 *
 * @author xiafu
 *
 */
public class RandomAssignmentStrategy implements SegmentAssignmentStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(RandomAssignmentStrategy.class);

  @Override
  public List<String> getAssignedInstances(HelixAdmin helixAdmin, String helixClusterName,
      SegmentMetadata segmentMetadata, int numReplicas) {
    String resourceName = null;
    if ("realtime".equalsIgnoreCase(segmentMetadata.getIndexType())) {
      resourceName = BrokerRequestUtils.getRealtimeResourceNameForResource(segmentMetadata.getResourceName());
    } else {
      resourceName = BrokerRequestUtils.getOfflineResourceNameForResource(segmentMetadata.getResourceName());
    }
    final Random random = new Random(System.currentTimeMillis());
    List<String> allInstanceList =
        helixAdmin.getInstancesInClusterWithTag(helixClusterName, resourceName);
    List<String> selectedInstanceList = new ArrayList<String>();
    for (int i = 0; i < numReplicas; ++i) {
      final int idx = random.nextInt(allInstanceList.size());
      selectedInstanceList.add(allInstanceList.get(idx));
      allInstanceList.remove(idx);
    }
    LOGGER.info("Segment assignment result for : " + segmentMetadata.getName() + ", in resource : "
        + segmentMetadata.getResourceName() + ", selected instances: "
        + Arrays.toString(selectedInstanceList.toArray()));

    return selectedInstanceList;
  }
}
