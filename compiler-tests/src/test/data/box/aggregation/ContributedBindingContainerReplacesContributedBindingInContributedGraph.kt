interface MyType

@ContributesBinding(AppScope::class)
@Inject
class Impl1 : MyType

@Inject
class Impl2 : MyType

@ContributesTo(AppScope::class, replaces = [Impl1::class])
@BindingContainer
interface ContributedContainer {
  @Binds fun bindMyType(
    impl: Impl2
  ): MyType
}

@ContributesGraphExtension(scope = AppScope::class)
interface ExampleGraph {
  val myType: MyType

  @ContributesGraphExtension.Factory(Unit::class)
  interface Factory {
    fun createExampleGraph(): ExampleGraph
  }
}

@DependencyGraph(scope = Unit::class, isExtendable = true)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>().createExampleGraph()
  assertEquals(graph.myType.javaClass.simpleName, "Impl2")
  return "OK"
}
