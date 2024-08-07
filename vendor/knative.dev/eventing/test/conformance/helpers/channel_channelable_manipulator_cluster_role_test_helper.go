/*
Copyright 2020 The Knative Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package helpers

import (
	"context"
	"testing"

	"fmt"

	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apiserver/pkg/storage/names"

	testlib "knative.dev/eventing/test/lib"
)

// Deprecated
func TestChannelChannelableManipulatorClusterRoleTestRunner(
	ctx context.Context,
	t *testing.T,
	channelTestRunner testlib.ComponentsTestRunner,
	options ...testlib.SetupClientOption,
) {

	const aggregationClusterRoleName = "channelable-manipulator"
	var permissionTestCaseVerbs = []string{"get", "list", "watch", "update", "patch", "delete"}

	channelTestRunner.RunTests(t, testlib.FeatureBasic, func(st *testing.T, channel metav1.TypeMeta) {
		client := testlib.Setup(st, true, options...)
		defer testlib.TearDown(client)

		gvr, _ := meta.UnsafeGuessKindToResource(channel.GroupVersionKind())

		saName := names.SimpleNameGenerator.GenerateName("conformance-test-channel-manipulator-")
		client.CreateServiceAccountOrFail(saName)
		client.CreateClusterRoleBindingOrFail(
			saName,
			aggregationClusterRoleName,
			saName+"-cluster-role-binding",
		)
		client.WaitForAllTestResourcesReadyOrFail(ctx)

		// From spec: (...) ClusterRole MUST include permissions to create, get, list, watch, patch,
		// and update the CRD's custom objects and their status.
		for _, verb := range permissionTestCaseVerbs {
			t.Run(fmt.Sprintf("ChannelableManipulatorClusterRole can do %s on %s", verb, gvr), func(t *testing.T) {
				ServiceAccountCanDoVerbOnResourceOrFail(client, gvr, "", saName, verb)
			})
			t.Run(fmt.Sprintf("ChannelableManipulatorClusterRole can do %s on status subresource of %s", verb, gvr), func(t *testing.T) {
				ServiceAccountCanDoVerbOnResourceOrFail(client, gvr, "status", saName, verb)
			})
		}
	})
}
