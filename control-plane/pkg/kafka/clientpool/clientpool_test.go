/*
 * Copyright 2023 The Knative Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package clientpool

import (
	"context"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/stretchr/testify/assert"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	kafkatesting "knative.dev/eventing-kafka-broker/control-plane/pkg/kafka/testing"
	"knative.dev/eventing-kafka-broker/control-plane/pkg/prober"
)

func TestGetClient(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithTimeout(context.Background(), time.Second*30)

	cache := prober.NewLocalExpiringCache[clientKey, *client, struct{}](ctx, time.Minute*30)

	clients := &ClientPool{
		Cache: cache,
		newSaramaClient: func(_ []string, _ *sarama.Config) (sarama.Client, error) {
			return &kafkatesting.MockKafkaClient{}, nil
		},
		newClusterAdminFromClient: func(_ sarama.Client) (sarama.ClusterAdmin, error) {
			return &kafkatesting.MockKafkaClusterAdmin{ExpectedTopics: []string{"topic1"}}, nil
		},
	}

	client1, returnClient1, err := clients.GetClient(ctx, []string{"localhost:9092"}, nil)

	assert.NoError(t, err)

	controller, err := client1.Controller()
	assert.NoError(t, err)
	assert.NotNil(t, controller)

	client2, returnClient2, err := clients.GetClient(ctx, []string{"localhost:9092"}, nil)

	assert.NoError(t, err)

	controller, err = client2.Controller()
	assert.NoError(t, err)
	assert.NotNil(t, controller)

	client3, returnClient3, err := clients.GetClient(ctx, []string{"localhost:9092"}, nil)
	assert.NoError(t, err)

	controller, err = client3.Controller()
	assert.NoError(t, err)
	assert.NotNil(t, controller)

	returnClient1(nil)
	returnClient2(nil)
	returnClient3(nil)

	cancel()
}

func TestGetClusterAdmin(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithTimeout(context.Background(), time.Second*30)

	cache := prober.NewLocalExpiringCache[clientKey, *client, struct{}](ctx, time.Minute*30)

	clients := &ClientPool{
		Cache: cache,
		newSaramaClient: func(_ []string, _ *sarama.Config) (sarama.Client, error) {
			return &kafkatesting.MockKafkaClient{}, nil
		},
		newClusterAdminFromClient: func(_ sarama.Client) (sarama.ClusterAdmin, error) {
			return &kafkatesting.MockKafkaClusterAdmin{ExpectedTopics: []string{"topic1"}}, nil
		},
	}

	client1, returnClient1, err := clients.GetClusterAdmin(ctx, []string{"localhost:9092"}, nil)

	assert.NoError(t, err)

	topics, err := client1.ListTopics()
	assert.NoError(t, err)
	assert.Contains(t, topics, "topic1")

	client2, returnClient2, err := clients.GetClusterAdmin(ctx, []string{"localhost:9092"}, nil)

	assert.NoError(t, err)

	topics, err = client2.ListTopics()
	assert.NoError(t, err)
	assert.Contains(t, topics, "topic1")

	client3, returnClient3, err := clients.GetClusterAdmin(ctx, []string{"localhost:9092"}, nil)
	assert.NoError(t, err)

	topics, err = client3.ListTopics()
	assert.NoError(t, err)
	assert.Contains(t, topics, "topic1")

	returnClient1(nil)
	returnClient2(nil)
	returnClient3(nil)

	cancel()
}

func TestMakeClientKey(t *testing.T) {
	key1 := makeClusterAdminKey([]string{"localhost:9090", "localhost:9091", "localhost:9092"}, &corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: "secret", Namespace: "knative-eventing"}})
	key2 := makeClusterAdminKey([]string{"localhost:9092", "localhost:9091", "localhost:9090"}, &corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: "secret", Namespace: "knative-eventing"}})
	key3 := makeClusterAdminKey([]string{"localhost:9090", "localhost:9091", "localhost:9092"}, nil)

	assert.Equal(t, key1, key2)
	assert.NotEqual(t, key1, key3)
}