(ns kubedi.core-test
  (:require [midje.sweet :refer :all]
            [kubedi.core :as kdi]
            [yaml.core :as yaml]
            [clojure.string :as str]))

;; # Basic API Object Functions

;; The following functions create basic API objects.

;; The `pod` function creates a pod with no containers.
(fact
 (kdi/pod :foo {:app :bar})
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {:app :bar}}
     :spec {:containers []}})

;; `pod` can take a third argument with additional spec parameters.
(fact
 (kdi/pod :foo {:app :bar} {:foo :bar})
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {:app :bar}}
     :spec {:containers []
            :foo :bar}})

;; The `deployment` function creates a deployment, based on the given
;; pod as template. The deployment takes its name from the given pod,
;; and removes the name from the template.
(fact
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image")
     (kdi/deployment 3))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}}})

;; The `stateful-set` function wraps the given pod with a Kubernetes
;; stateful set.
(fact
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image")
     (kdi/stateful-set 5))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :serviceName :foo
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}
            :volumeClaimTemplates []}})

;; # Modifier Functions

;; The following functions augment basic API objects by adding
;; content. They always take the API object as a first argument.

;; The `add-container` function adds a container to a pod. The
;; function takes the container name and the image to be used as
;; explicit parameters, and an optional map with additional parameters.
(fact
 (-> (kdi/pod :foo {})
     (kdi/add-container :bar "bar-image" {:ports [{:containerPort 80}]}))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :ports [{:containerPort 80}]}]}})

;; `add-env` augments the parameters of a _container_, and adds an
;; environment variable binding.
(fact
 (-> (kdi/pod :foo {})
     (kdi/add-container :bar "bar-image" (-> {:ports [{:containerPort 80}]}
                                             (kdi/add-env {:FOO "BAR"}))))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :ports [{:containerPort 80}]
                          :env [{:name :FOO
                                 :value "BAR"}]}]}})

;; If an `:env` key already exists, new entries are added to the list.
(fact
 (-> (kdi/pod :foo {})
     (kdi/add-container :bar "bar-image" (-> {:env [{:name :QUUX :value "TAR"}]}
                                             (kdi/add-env {:FOO "BAR"}))))
 => {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name :foo
                :labels {}}
     :spec {:containers [{:name :bar
                          :image "bar-image"
                          :env [{:name :QUUX
                                 :value "TAR"}
                                {:name :FOO
                                 :value "BAR"}]}]}})

;; The `add-volume-claim-template` function takes a stateful-set, adds
;; a volume claim template to its spec and mounts it to the given
;; paths within the given containers.
(fact
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image")
     (kdi/add-container :baz "some-other-image")
     (kdi/stateful-set 5 {:additional-arg 123})
     (kdi/add-volume-claim-template :vol-name
                                    ;; Spec
                                    {:accessModes ["ReadWriteOnce"]
                                     :storageClassName :my-storage-class
                                     :resources {:requests {:storage "1Gi"}}}
                                    ;; Mounts
                                    {:bar "/var/lib/foo"}))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :serviceName :foo
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :volumeMounts
                [{:name :vol-name
                  :mountPath "/var/lib/foo"}]}
               {:name :baz
                :image "some-other-image"}]}}
            :volumeClaimTemplates
            [{:metadata {:name :vol-name}
              :spec {:accessModes ["ReadWriteOnce"]
                     :storageClassName :my-storage-class
                     :resources {:requests {:storage "1Gi"}}}}]
            :additional-arg 123}})

;; If the `:volumeMounts` entry already exists in the container, the
;; new mount is appended.
(fact
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image" {:volumeMounts [{:foo :bar}]})
     (kdi/stateful-set 5)
     (kdi/add-volume-claim-template :vol-name
                                    ;; Spec
                                    {:accessModes ["ReadWriteOnce"]
                                     :storageClassName :my-storage-class
                                     :resources {:requests {:storage "1Gi"}}}
                                    ;; Mounts
                                    {:bar "/var/lib/foo"}))
 => {:apiVersion "apps/v1"
     :kind "StatefulSet"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 5
            :selector
            {:matchLabels {:bar :baz}}
            :serviceName :foo
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :volumeMounts
                [{:foo :bar}
                 {:name :vol-name
                  :mountPath "/var/lib/foo"}]}]}}
            :volumeClaimTemplates
            [{:metadata {:name :vol-name}
              :spec {:accessModes ["ReadWriteOnce"]
                     :storageClassName :my-storage-class
                     :resources {:requests {:storage "1Gi"}}}}]}})

;; ## Update Functions

;; While `add-*` functions are good for creating new API objects, we
;; sometimes need to update existing ones. For example, given a
;; deployment, we sometimes want to add an environment to one of the
;; containers in the template.

;; `update-*` work in a similar manner to Clojure's `update`
;; function. It takes an object to be augmented, an augmentation
;; function which takes the object to update as its first argument,
;; and additional arguments for that function. Then it applies the
;; augmentation function on a portion of the given object, and returns
;; the updated object.

;; `update-template` operates on controllers (deployments,
;; stateful-sets, etc). It takes a pod-modifying function and applies
;; it to the template. For example, we can use it to add a container
;; to a pod already within a deployment.
(fact
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/deployment 3)
     ;; The original pod has no containers. We add one now.
     (kdi/update-template kdi/add-container :bar "some-image"))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"}]}}}})

;; `update-container` works on a pod. It takes a container name, and
;; applies the augmentation function with its arguments on the
;; container with the given name. It can be used in conjunction with
;; `update-template` to operate on a controller.
(fact
 (-> (kdi/pod :foo {:bar :baz})
     (kdi/add-container :bar "some-image")
     (kdi/add-container :baz "some-other-image")
     (kdi/deployment 3)
     ;; We add an environment to a container.
     (kdi/update-template kdi/update-container :bar kdi/add-env {:FOO "BAR"}))
 => {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name :foo
                :labels {:bar :baz}}
     :spec {:replicas 3
            :selector
            {:matchLabels {:bar :baz}}
            :template
            {:metadata
             {:labels {:bar :baz}}
             :spec
             {:containers
              [{:name :bar
                :image "some-image"
                :env [{:name :FOO
                       :value "BAR"}]}
               {:name :baz
                :image "some-other-image"}]}}}})

;; # Exposure Functions

;; There are several ways to expose a service under Kubernetes. The
;; `expose*` family of functions wraps an existing deployment with a
;; list, containing the deployment itself (unchanged) and a service,
;; which exposes it.

;; The `expose` function is the most basic among them. The service it
;; provides takes its spec as argument.
(fact
 (-> (kdi/pod :nginx-deployment {:app :nginx})
     (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
     (kdi/deployment 3)
     (kdi/expose {:ports [{:protocol :TCP
                           :port 80
                           :targetPort 9376}]}))
 => [{:apiVersion "apps/v1"
      :kind "Deployment"
      :metadata {:labels {:app :nginx} :name :nginx-deployment}
      :spec {:replicas 3
             :selector {:matchLabels {:app :nginx}}
             :template {:metadata {:labels {:app :nginx}}
                        :spec {:containers [{:image "nginx:1.7.9"
                                             :name :nginx
                                             :ports [{:containerPort 80}]}]}}}}
     {:kind "Service"
      :apiVersion "v1"
      :metadata {:name :nginx-deployment}
      :spec
      {:selector {:app :nginx}
       :ports [{:protocol :TCP
                :port 80
                :targetPort 9376}]}}])


;; The `expose-headless` wraps the given controller (deployment,
;; statefulset) with a headless service. The service exposes all the
;; ports listed as `:containerPort`s in all the containers in the
;; controller's template. For ports with a `:name`, the name is also
;; copied over.
(fact
 (-> (kdi/pod :nginx-deployment {:app :nginx})
     (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80 :name :web}]})
     (kdi/add-container :sidecar "my-sidecar" {:ports [{:containerPort 3333}]})
     (kdi/deployment 3)
     (kdi/expose-headless))
 => [{:apiVersion "apps/v1"
      :kind "Deployment"
      :metadata {:labels {:app :nginx}
                 :name :nginx-deployment}
      :spec {:replicas 3
             :selector {:matchLabels {:app :nginx}}
             :template
             {:metadata {:labels {:app :nginx}}
              :spec {:containers
                     [{:image "nginx:1.7.9"
                       :name :nginx
                       :ports [{:containerPort 80
                                :name :web}]}
                      {:image "my-sidecar"
                       :name :sidecar
                       :ports [{:containerPort 3333}]}]}}}}
     {:kind "Service"
      :apiVersion "v1"
      :metadata {:name :nginx-deployment}
      :spec
      {:selector {:app :nginx}
       :clusterIP :None
       :ports [{:port 80 :name :web}
               {:port 3333}]}}])


;; # Dependency Injection

;; Functions such as `pod` and `deployment` help build Kubernetes API
;; objects. If we consider Kubedi to be a language, these are the
;; _common nouns_. They can be used to build general Pods,
;; Deployments, StatefulSets, etc, and can be used to develop other
;; functions that create general things such as a generic Redis
;; database, or a generic Nginx deployment, which can also be
;; represented as a function.

;; However, when we go down to the task of defining a system, we need
;; a way to define _proper nouns_, such as _our_ Redis database and
;; _our_ Nginx deployment.

;; This distinction is important because when creating a generic Nginx
;; deployment, it stands on its own, and is unrelated to any Redis
;; database that may or may not be used in conjunction with
;; it. However, when we build our application, which happens to have
;; some, e.g., PHP code running on top of Nginx, which happens to
;; require a database, this is when we need the two to be
;; connected. We need to connect them by, e.g., adding environment
;; variables to the Nginx container, so that PHP code that runs over
;; it will be able to connect to the database.

;; This is where dependency injection comes in. Dependency Injection
;; (DI) is a general concept that allows developers to define proper
;; nouns in their software in an incremental way. It starts with some
;; configuration, which provides arbitrary settings. Then a set of
;; resources is being defined. Each such resource may depend on other
;; resources, including configuration.

;; Our implementation of DI, resources are identified with symbols,
;; corresponding to the proper nouns. These nouns are defined in
;; functions, named _modules_, which take a single parameter -- an
;; _injector_ (marked as `$` by convention), and augment it by adding
;; new rules to it.
(defn module1 [$]
  (-> $
      (kdi/rule :my-deployment []
                (fn []
                  [;; An API object to be deployed
                   (-> (kdi/pod :my-pod {:app :my-app})
                       (kdi/deployment 3))
                   ;; An arbitrary value that represents it.
                   {:foo :bar}]))))

;; This module defines one `rule`, defining `:my-deployment`. The
;; empty vector indicates no dependencies. The function provides the
;; logic creating this resource. It returns a vector with two
;; elements. The first is the API object(s) that represent this
;; resource, and the second is a value that provides information about
;; this resource. The latter will be passed to rules that are based on
;; this resource.

;; With this module in place, we can create the deployment by:
;; 1. Creating an empty injector, by calling the `injector` function (which takes a configuration map, which we will leave empty),
;; 2. Calling the module function to augment this injector (module functions would typically be threaded on the injector).
;; 3. Calling `get-resource` to get API objects for the resource and everything it is based on.
(fact
 (-> (kdi/injector {})
     module1
     (kdi/get-resource :my-deployment))
 => [[(-> (kdi/pod :my-pod {:app :my-app})
          (kdi/deployment 3))]
     {:foo :bar}])

;; Dependencies provided to the `rule` function are resolved before
;; the function is called. For example, imagine we would like to make
;; the number of replicas parametric, we can make it a dependency.
(defn module2 [$]
  (-> $
      (kdi/rule :my-deployment [:my-depl-num-replicas]
                (fn [num-replicas]
                  [(-> (kdi/pod :my-pod {:app :my-app})
                       (kdi/deployment num-replicas))
                   {:foo :bar}]))))

;; Now we can provide this parameter in the config we provide the
;; `injector` function.
(fact
 (-> (kdi/injector {:my-depl-num-replicas 5})
     module2
     (kdi/get-resource :my-deployment))
 => [[(-> (kdi/pod :my-pod {:app :my-app})
          (kdi/deployment 5))]
     {:foo :bar}])

;; Rules can depend on one another. For example, in the following
;; module the pod we define adds the description map we got from the
;; `:my-deployment` as environment for its container.
(defn module3 [$]
  (-> $
      (kdi/rule :my-pod [:my-deployment]
                (fn [my-depl]
                  [(-> (kdi/pod :my-pod {:app :my-app})
                       (kdi/add-container :foo "foo-image"
                                          (-> {}
                                              (kdi/add-env my-depl))))
                   {:pod-name :my-pod}]))))

;; Now, if we create an injector based on rules for both `:my-pod` and
;; `:my-deployment`, when we request `:my-pod` we will get both the
;; deployment and the pod's API objects.
(fact
 (-> (kdi/injector {})
     module1
     module3
     (kdi/get-resource :my-pod))
 => [[;; The deployment
      (-> (kdi/pod :my-pod {:app :my-app})
          (kdi/deployment 5))
      ;; The pod
      (-> (kdi/pod :my-pod {:app :my-app})
          (kdi/add-container :foo "foo-image"
                             (-> {}
                                 (kdi/add-env {:foo :bar}))))]
     {:pod-name :my-pod}])

;; # Turning this to Usable YAML Files

(defn to-yaml [objs]
  (->> objs
       (map #(yaml/generate-string % :dumper-options {:flow-style :block :scalar-style :plain}))
       (str/join "---\n")))

'(println (-> (kdi/pod :nginx-deployment {:app :nginx})
             (kdi/add-container :nginx "nginx:1.7.9" {:ports [{:containerPort 80}]})
             (kdi/deployment 3)
             (kdi/expose-headless)
             (to-yaml)))

'(println (-> (kdi/pod :nginx {:app :nginx} {:terminationGracePeriodSeconds 10})
             (kdi/add-container :nginx "k8s.gcr.io/nginx-slim:0.8" {:ports [{:containerPort 80
                                                                             :name "web"}]})
             (kdi/stateful-set 3)
             (kdi/add-volume-claim-template :www
                                            {:accessModes ["ReadWriteOnce"]
                                             :resources {:requests {:storage "1Gi"}}}
                                            {:nginx "/usr/share/nginx/html"})
             (kdi/expose-headless)
             (to-yaml)))